(ns aws-snapshot-monkey.core
  (:gen-class)
  (:require 
    [uswitch.lambada.core :refer [deflambdafn]]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]
    [clj-time.core :as tm]
    [clojure.tools.cli :refer [cli]]
    [amazonica.core :refer [with-credential defcredential ]]
    [amazonica.aws.ec2 :refer 
     [describe-images describe-snapshots describe-instances 
      describe-volumes delete-snapshot create-snapshot]]
    [robert.bruce :refer [try-try-again]]
    [clojure.walk :refer [keywordize-keys]]
    [clojure.set :refer [difference]]
    [clojure.pprint :refer [pprint]]
    ;;            [clojure.string :refer [split]]
    )) 

;;TODO: specl testing

(defn handler [request]
  "handles http requests"
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "I am Snapshot Monkey!\n"})

(defn derive_set [seqofhashes keytoget]
  "Turns a map into a set for a given key"
  (into #{}
        (map
          (fn [m] (get m keytoget))
          seqofhashes
          )))

(defn get_volumes_in_use [region] 
  "grab the aws volume-id's for in-use volumes"
  (get (describe-volumes {:endpoint region}
                         :filters 
                         [{:name "attachment.status" :values ["attached"]}
                          {:name "status" :values ["in-use"]}] 
                         :owners ["self"]) :volumes)
  )

(defn get_snapshots_for_volumes [region account volumes]
  "fetch the snapshot-id's and volume-id's for given volumes"
  (flatten
    ;;aws will only accept 200 filter args at a time
    (for [part (partition-all 190 volumes)]
      (map
        (fn [m] (select-keys m [:snapshot-id :volume-id :start-time]))
        (get 
          (describe-snapshots {:endpoint region} 
                              :filters 
                              [{:name "volume-id" :values part}])
                              :snapshots )
        ))))

(defn get_snapshots [region account]
  "fetch all the snapshots"
  (map
    (fn [m] (select-keys m [:snapshot-id :volume-id :start-time]))
    (get 
      (describe-snapshots {:endpoint region}
                          :filters 
                          [{:name "owner-id"
                            :value account}])
      :snapshots)))

(defn get_images [region]
  "fetch all the images"
  (map
    (fn [m] (select-keys m [:image-id :block-device-mappings :name]))
    (get
      (describe-images {:endpoint region} :owners ["self"] )
      :images)))

(defn prune_snapshots [region snapshots]
  "prune snapshots"
  (println "Deleting snapshots: " snapshots)
  (map (fn [m] (try-try-again
                 delete-snapshot {:endpoint region} :snapshot-id m))
       snapshots
       ))

(defn filter_by_start_time [days_ago snapshots]
  "take a list of snapshots and return only those elements with a recent start
  time"
  (map (fn [m] 
         (if (tm/before? (-> days_ago tm/days tm/ago) (get m :start-time))
           m ))
       snapshots))

(defn snapshot_volumes [region volumes]
  "snapshot a list of volumes"
  (println "Snapshotting volumes: " volumes)
  (map (fn [m] (try-try-again 
                 create-snapshot {:endpoint region} 
                 :volume-id m :Description "Snapshot Monkey"))
       volumes
       ))

(defn snapshot_volumes_handler [event-json]
  (def event (keywordize-keys event-json))
  (def region (:region event))
  (println "Executing in region: " region)
  (def account (:account event))
  (println "Executing in account: " account)
  (def days_ago 7)
  (println "Maximum snapshot age: " days_ago)
  (def mock (get-in event [:details :mock]))
  (def volumes_in_use (derive_set 
                        (get_volumes_in_use region) :volume-id))
  (println "Volumes in use: " (count volumes_in_use))
  (def snaps_in_use (get_snapshots_for_volumes 
                      region account volumes_in_use ))
  (println "Snapshots in use: " (count snaps_in_use))
  (def snaps_wo_vols (difference
                       (derive_set 
                         (get_snapshots region account) :snapshot-id) 
                       (derive_set snaps_in_use :snapshot-id)))
  (def snaps_defunct
    (difference snaps_wo_vols
                (into #{}
                      (map #(get-in % [:ebs :snapshot-id])
                           (flatten 
                             (map 
                               #(get % :block-device-mappings) 
                               (get_images region)))))))

  (def vols_with_recent_snaps 
    (into #{} 
          (keys 
            (group-by :volume-id 
                      (filter_by_start_time 
                        days_ago snaps_in_use)))))

  (println "Found volume(s) with recent snapshots: " 
           (count vols_with_recent_snaps))
  (def vols_wo_snaps (difference volumes_in_use vols_with_recent_snaps))
  (println "Found " (count vols_wo_snaps) " volumes to snapshot")
  (println "Found " (count snaps_defunct) " snapshots to delete")
  (when-not mock
    (println "Doing work:")
    (dorun (snapshot_volumes region vols_wo_snaps))
    (dorun (prune_snapshots region snaps_defunct))
    (println "Done")
    )
  )
  
;Lambada function example event
;{
;  "account": "123456789012",
;  "region": "us-east-1",
;  "detail": {},
;  "detail-type": "Scheduled Event",
;  "source": "aws.events",
;  "time": "1970-01-01T00:00:00Z",
;  "id": "cdc73f9d-aea9-11e3-9d5a-835b769c0d9c",
;  "resources": [
;    "arn:aws:events:us-east-1:123456789012:rule/my-schedule"
;  ]
;}

(deflambdafn com.cronus.momentomori [in out ctx]
  (let [event (json/read (io/reader in))
        res (snapshot_volumes_handler event)]
    (with-open [w (io/writer out)]
      (json/write res w)))
  )
