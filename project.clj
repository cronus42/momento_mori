(defproject aws_snapshot_monkey "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"][amazonica "0.2.21"]
                 [org.clojure/tools.cli "0.2.4"][clj-time "0.7.0"]
                 [org.clojure/tools.logging "0.3.0"]]
  :main ^:skip-aot aws-snapshot-monkey.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
