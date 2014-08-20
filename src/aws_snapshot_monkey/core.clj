(ns aws-snapshot-monkey.core
  (:gen-class)
  (:require [clj-time.core :as tm]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.jobs :refer [defjob]]
            [clojurewerkz.quartzite.triggers :as t]
            [clojurewerkz.quartzite.schedule.calendar-interval :refer 
             [schedule with-interval-in-minutes]]
            [clojurewerkz.quartzite.conversion :as qc]
            [amazonica.core :refer [with-credential defcredential]])
  (:use [clj-time.coerce])
  (:use [amazonica.aws.ec2])
  (:use [amazonica.aws.identitymanagement])
  (:use [clojure.string :only (split)])
  (:use [clojure.pprint])
  (:use [clojure.set])
  (:use [clojure.walk])
  (:use [robert.bruce :only [try-try-again]])
  )

;;TODO: specl testing
;;TODO: config file

(defn handler [request]
    {:status 200
        :headers {"Content-Type" "text/html"}
        :body "I am Snapshot Monkey!"})

(defn get_account_id []
  ;((split (get-in (get-user) [:user :arn]) #":") 4)
  (get (first (get (describe-instances) :reservations)) :owner-id)
  )

(defn derive_set [seqofhashes keytoget]
  (into #{}
        (map
          (fn [m] (get m keytoget))
          seqofhashes
          )))


(defn get_volumes_in_use [] 
  "grab the aws volume-id's for in-use volumes"
  (get (describe-volumes 
         :filters [{:name "attachment.status" :values ["attached"]}
                   {:name "status" :values ["in-use"]}] 
         :owners ["self"]) :volumes)
  )

(defn get_snapshots_for_volumes [volumes]
  "fetch the snapshot-id's and volume-id's for given volumes"
  (flatten
    ;;aws will only accept 200 filter args at a time
    (for [part (into [] (partition-all 190 volumes))]
      (map
        (fn [m] (select-keys m [:snapshot-id :volume-id :start-time]))
        (get 
          (describe-snapshots 
            :filters [{:name "volume-id" :values part}
                      {:name "owner-id" :values [(get_account_id)]}] )
      :snapshots))
    )))

(defn get_snapshots []
  "fetch all the snapshots"
  (map
    (fn [m] (select-keys m [:snapshot-id :volume-id :start-time]))
    (get 
      (describe-snapshots 
        :filters [{:name "owner-id" :values [(get_account_id)]}])
      :snapshots)))

(defn get_images []
  "fetch all the images"
  (map
    (fn [m] (select-keys m [:image-id :block-device-mappings :name]))
    (get
      (describe-images :owners ["self"] )
      :images)))

(defn prune_snapshots [snapshots]
  "prune snapshots"
    (log/debug "Deleting snapshots: " snapshots)
    (map (fn [m] (try-try-again
             delete-snapshot :snapshot-id m))
         snapshots
         ))

(defn filter_by_start_time [days_ago snapshots]
  "take a list of snapshots and return only those elements with a recent start
  time"
  (map (fn [m] 
         (if (tm/before? (-> days_ago tm/days tm/ago) (get m :start-time))
           m ))
       snapshots))

(defn snapshot_volumes [volumes]
  "snapshot a list of volumes"
  (log/debug "Snapshotting volumes: " volumes)
  (map (fn [m] (try-try-again 
         create-snapshot :volume-id m :Description "Snapshot Monkey"))
       volumes
       ))

(defjob snapshot_volumes_job [ctx]
  (let [options (keywordize-keys (qc/from-job-data ctx))]
    (log/info "Running scheduled job")
    (def volumes_in_use (derive_set (get_volumes_in_use) :volume-id))
    (def snaps_in_use (get_snapshots_for_volumes volumes_in_use))
    (def snaps_wo_vols (difference
                         (derive_set (get_snapshots) :snapshot-id) 
                         (derive_set snaps_in_use :snapshot-id)))
    (def snaps_defunct
      (difference snaps_wo_vols
                  (into #{}
                        (map #(get-in % [:ebs :snapshot-id])
                             (flatten 
                                  (map 
                                    #(get % :block-device-mappings) 
                                    (get_images))))
                        )))
    (def vols_with_recent_snaps 
      (into #{} 
            (keys 
              (group-by :volume-id 
                        (filter_by_start_time 
                          (:days-old options) snaps_in_use)))))

    (def vols_wo_snaps (difference volumes_in_use vols_with_recent_snaps))
    (log/info "Found " (count vols_wo_snaps) " volumes to snapshot")
    (dorun (snapshot_volumes vols_wo_snaps))
    (when (:prune options)
      (log/info "Found " (count snaps_defunct) " snapshots to delete")
      (dorun (prune_snapshots snaps_defunct))
      )
    (log/info "Run finished") 
    ))

(defn -main [& args]
  "The main function"
  ;;parse opts
  (let  [[options args banner]
         (cli/cli args
                  ["-d" "--days-old" "Maximum age of a snapshot" :default 5
                   :parse-fn #(Integer. %)]
                  ["-f" "--frequency" "Run frequency in minutes" :default 30
                   :parse-fn #(Integer. %)]
                  ["-r" "--region" "AWS Region" :default "us-west-2"
                   ]
                  ["-p" "--prune" "Prune orphaned snaps" :default true
                   :flag true] 
                  ["-h" "--help" "Help" :default false :flag true]
                  )]

    (when (:help options)
            (println banner)
      (System/exit 0)
      )

    ;;Dig the credentials out of JDK
    
    ;(def aws_access_key_id 
    ;  (.getAWSAccessKeyId 
    ;    (.getCredentials (amazonica.core/get-credentials :cred))))
    ;(def aws_secret_key 
    ;  (.getAWSSecretKey 
    ;    (.getCredentials (amazonica.core/get-credentials :cred))))

    ;(defcredential aws_access_key_id aws_secret_key (:region options))


    (log/info "Executing in account" (get_account_id))
    ;;set up the scheduler
    (qs/initialize)
    (qs/start)
    (log/info "Starting snapshot job with frequency of " (:frequency options))
    (let [job (j/build
                (j/of-type snapshot_volumes_job)
                (j/using-job-data options)
                (j/with-identity (j/key "jobs.snapshot_volumes")))
          trigger (t/build
                    (t/with-identity (t/key "triggers.1"))
                    (t/start-now)
                    (t/with-schedule 
                      (schedule
                        (with-interval-in-minutes (:frequency options))
                        )))]


      (qs/schedule job trigger)
      )

    ))
