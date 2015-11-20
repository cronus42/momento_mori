(defproject aws_snapshot_monkey "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-ring "0.8.11"][speclj "2.9.0"]] 
  :dependencies [[org.clojure/clojure "1.6.0"][amazonica "0.2.21"]
                 [org.clojure/tools.cli "0.2.4"][clj-time "0.7.0"]
                 [ring/ring-core "1.3.0"]
                 [ring/ring-jetty-adapter "1.3.0"]
                 [org.clojure/tools.logging "0.3.0"]
                 [org.slf4j/slf4j-simple "1.6.1"]
                 [clojurewerkz/quartzite "1.2.0"]
                 [clj-http "1.0.0"]
                 [robert/bruce "0.7.1"]]
  :dev-dependencies [[speclj "2.9.0"]]
  :main ^:skip-aot aws-snapshot-monkey.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :test-paths ["spec"]
  :ring {:handler aws-snapshot-monkey.core/handler
         :init aws-snapshot-monkey.core/-main})
