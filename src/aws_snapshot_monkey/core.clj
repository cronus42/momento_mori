(ns aws-snapshot-monkey.core
  (:gen-class)
  (:require [clj-time.core :as tm]
            [clj-time.local :as loc]
            [clj-time.format :as fmt]
            [clojure.tools.cli :as cli])
  (:use clj-time.coerce)
  (:use [amazonica.aws.ec2])
  (:use [clojure.pprint])
  (:use [clojure.set]))

(def opts [["-d" "--days-old" :default 30
                                  :parse-fn #(Integer. %)
                                  :assoc-fn max]])

(defn get_volumes_in_use [] 
  "grab the aws volume-id's for in-use volumes"
  (map 
    (fn [m] (get m :volume-id))
    (get (describe-volumes :state "in-use") :volumes)
    ))

(defn get_snapshots_for_volumes [volumes]
  "fetch the snapshot-id's and volume-id's for given volumes"
  (map
    (fn [m] (select-keys m [:snapshot-id :volume-id :start-time]))
    (get 
      (describe-snapshots :filters [{:name "volume-id" :values volumes}])
      :snapshots)))

(defn filter_by_start_time [days_ago snapshots]
  "take a list of snapshots and return only those elements with a recent start
  time"
  (map (fn [m] 
         (if (tm/before? (-> days_ago tm/days tm/ago) (get m :start-time))
          m ))
       snapshots))

(defn -main [& args]
  "The main function"
  (def volumes_in_use (into #{} (get_volumes_in_use)))
  (def snaps_in_use (get_snapshots_for_volumes volumes_in_use))
  (def vols_with_recent_snaps 
    (into #{} 
          (keys (group-by :volume-id (filter_by_start_time 60 snaps_in_use)))))
  (def vols_wo_snaps (difference volumes_in_use vols_with_recent_snaps))
  (println "In-use volumes without recent snapshots:")
  (pprint vols_wo_snaps)
  )

