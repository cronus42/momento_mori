(ns aws-snapshot-monkey.core
  (:gen-class)
  (:require [clj-time.core :as tm]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.jobs :refer [defjob]]
            [clojurewerkz.quartzite.triggers :as t]
            [clojurewerkz.quartzite.schedule.cron 
             :refer [schedule cron-schedule]]
            [clojurewerkz.quartzite.conversion :as qc]
            [amazonica.core :refer [with-credential defcredential]])
  (:use [clj-time.coerce])
  (:use [amazonica.aws.ec2])
  (:use [clojure.pprint])
  (:use [clojure.set])
  (:use [clojure.walk]))

;;TODO: specl testing
;;TODO: config file

(defn get_volumes_in_use [] 
  "grab the aws volume-id's for in-use volumes"
  (into #{}
        (map 
          (fn [m] (get m :volume-id))
          (get (describe-volumes :state "in-use") :volumes)
          )))

(defn get_snapshots_for_volumes [volumes]
  "fetch the snapshot-id's and volume-id's for given volumes"
  (map
    (fn [m] (select-keys m [:snapshot-id :volume-id :start-time]))
    (get 
      (describe-snapshots :filters [{:name "volume-id" :values volumes}])
      :snapshots)))

(defn get_snapshots []
  "fetch all the snapshots"
  ;;TODO: Make sure we actually own them
  (into #{}
        (map (fn [m] (get m :snapshot-id))
             (get (describe-snapshots) :snapshots))))

(defn filter_by_start_time [days_ago snapshots]
  "take a list of snapshots and return only those elements with a recent start
  time"
  ;;TODO: Make sure they are completed?
  (map (fn [m] 
         (if (tm/before? (-> days_ago tm/days tm/ago) (get m :start-time))
           m ))
       snapshots))

(defn snapshot_volumes [volumes]
  "snapshot a list of volumes"
  (log/info "Snapshotting volumes: " volumes)
  (map (fn [m]
         (create-snapshot :volume-id m :Description "Snapshot Monkey"))
       volumes
       )
  )

(defjob snapshot_volumes_job [ctx]
  (let [options (keywordize-keys (qc/from-job-data ctx))]
    (def volumes_in_use (get_volumes_in_use))
    (def snaps_in_use (get_snapshots_for_volumes volumes_in_use))
    (def snaps_defunct (difference (get_snapshots) volumes_in_use))
    (def vols_with_recent_snaps 
      (into #{} 
            (keys 
              (group-by :volume-id 
                        (filter_by_start_time 
                          (:days-old options) snaps_in_use)))))
    (def vols_wo_snaps (difference volumes_in_use vols_with_recent_snaps))

    (log/debug (snapshot_volumes vols_wo_snaps) )

    ))

(defn -main [& args]
  "The main function"
  ;;parse opts
  (let  [[options args banner]
         (cli/cli args
                  ["-d" "--days-old" "Maximum age of a snapshot" :default 60
                   :parse-fn #(Integer. %)]
                  ["-f" "--frequency" "Run frequency in minutes" :default 30
                   :parse-fn #(Integer. %)]
                  ["-r" "--region" "AWS Region" :default "us-west-2"])]

    ;;Dig the credentials out of JDK
    (def aws_access_key_id 
      (.getAWSAccessKeyId 
        (.getCredentials (amazonica.core/get-credentials :cred))))
    (def aws_secret_key 
      (.getAWSSecretKey 
        (.getCredentials (amazonica.core/get-credentials :cred))))

    (defcredential aws_access_key_id aws_secret_key (:region options))


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
                        (cron-schedule 
                          (str "0 */" (:frequency options) " * * * ?")))))]

      (qs/schedule job trigger)
      )

    ))
