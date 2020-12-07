(defproject collection-check "0.1.7"
  :description "fuzz testing for alternate data structures"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/test.check "1.1.0"]
                 [com.gfredericks/test.chuck "0.2.10"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]]}})
