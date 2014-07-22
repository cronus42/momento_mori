(ns aws-snapshot-monkey.core
  (:gen-class)
  (:use [amazonica.aws.ec2])
  (:use [clojure.pprint]))

(defn get_volumes_in_use [] 
  "grab the aws volume-id's for in-use volumes"
        (map 
          (fn [m] (get m :volume-id))
          (get (describe-volumes :state "in-use") :volumes)
          ))

(defn get_snapshots_for_volumes [volumes]
  "fetch the snapshot-id's for a given volumes"
  (describe-snapshots :filters [{:name "volume-id" :values volumes}])
)

(defn -main []
  "The main function"
  (pprint (get_snapshots_for_volumes (get_volumes_in_use)))
  )

