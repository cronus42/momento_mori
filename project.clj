(defproject aws_snapshot_monkey "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-ring "0.8.11"][speclj "2.9.0"]] 
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [amazonica "0.3.39"]
                 [org.clojure/tools.cli "0.3.3"]
                 [clj-time "0.7.0"]
                 [ring/ring-core "1.3.0"]
                 [ring/ring-jetty-adapter "1.3.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-simple "1.6.1"]
                 [robert/bruce "0.7.1"]
                 [spyscope "0.1.5"]
                 [uswitch/lambada "0.1.0"]
                 [org.clojure/data.json "0.2.6"]]
  :dev-dependencies [[speclj "2.9.0"]]
  :main ^:skip-aot aws-snapshot-monkey.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :test-paths ["spec"]
  :ring {:handler aws-snapshot-monkey.core/handler
         :init aws-snapshot-monkey.core/-main})
