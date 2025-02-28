(ns collection-check.core
  (:require
    [clojure.string :as str]
    [clojure.test.check :refer (quick-check)]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    ;; Macros Clojure
    #?(:clj [clojure.test :as ct :refer [is]]
       :cljs [cljs.test :as ct :refer-macros [is]])
    #?(:clj [com.gfredericks.test.chuck.clojure-test :as chuck]
       :cljs [com.gfredericks.test.chuck.clojure-test :as chuck :include-macros true]))
  ;; Macros ClojureScript
  #?(:clj (:import [java.util Collection List Map])))

#?(:clj (set! *warn-on-reflection* false))

;;;

(defn gen-meta [gen]
  (gen/fmap
    (fn [[x meta]]
      (if #?(:clj (instance? clojure.lang.IObj x)
             :cljs (satisfies? cljs.core/IWithMeta x))
        (with-meta x {:foo meta})
        x))
    (gen/tuple
      gen
      (gen/one-of [gen/int (gen/return nil)]))))

(defn- tuple* [& args]
  (->> args
    (map
      #(if (and (map? %) (contains? % :gen))
         %
         (gen/return %)))
    (apply gen/tuple)))

(defn- ttuple [& args]
  (gen/tuple (apply tuple* args)))

;;;

(defn- seq-actions [element-generator]
  (gen/fmap
    (fn [[pre actions post]]
      (concat [pre] actions [post]))
    (tuple*
      [:seq]
      (gen/list
        (gen/one-of
          [(tuple* :rest)
           (tuple* :map-id)
           (tuple* :cons element-generator)
           (tuple* :conj element-generator)]))
      [:into])))

(defn- transient-actions [& intermediate-actions]
  (gen/fmap
    (fn [[pre actions post]]
      (concat [pre] actions [post]))
    (tuple*
      [:transient]
      (gen/list
        (gen/one-of
          (vec intermediate-actions)))
      [:persistent!])))

(defn- gen-vector-actions [element-generator transient? ordered?]
  (let [element-generator (gen-meta element-generator)
        standard [(ttuple :pop)
                  (ttuple :conj element-generator)
                  (ttuple :assoc (gen/choose 0 1e3) element-generator)]]
    (gen/list
      (gen/one-of
        (concat
          standard
          (when transient?
            [(transient-actions
               (tuple* :conj! element-generator)
               (tuple* :assoc! (gen/choose 0 999) element-generator)
               (tuple* :pop!))])
          (when ordered?
            [(seq-actions element-generator)]))))))

(defn- gen-map-actions [key-generator value-generator transient? ordered?]
  (let [key-generator (gen-meta key-generator)
        value-generator (gen-meta value-generator)
        standard [(ttuple :dissoc key-generator)
                  (ttuple :assoc key-generator value-generator)]]
    (gen/list
      (gen/one-of
        (concat
          standard
          (when transient?
            [(transient-actions
               (tuple* :dissoc! key-generator)
               (tuple* :assoc! key-generator value-generator))])
          (when ordered?
            [(seq-actions (tuple* key-generator value-generator))]))))))

(defn- gen-set-actions [element-generator transient? ordered?]
  (let [element-generator (gen-meta element-generator)
        standard [(ttuple :conj element-generator)
                  (ttuple :disj element-generator)]]
    (gen/list
      (gen/one-of
        (concat
          standard
          (when transient?
            [(transient-actions
               (tuple* :conj! element-generator)
               (tuple* :disj! element-generator))])
          (when ordered?
            [(seq-actions element-generator)]))))))

(defn- transient? [x]
  #?(:clj  (instance? clojure.lang.IEditableCollection x)
     :cljs (satisfies? cljs.core/IEditableCollection x)))

(defn- build-collections
  "Given a list of actions, constructs two parallel collections that can be compared
   for equivalencies."
  [coll-a coll-b vector-like? actions]
  (let [orig-a coll-a
        orig-b coll-b]
    (reduce
      (fn [[coll-a coll-b prev-actions] [action x y :as act]]

        ;; if it's a vector, we need to choose a valid index
        (let [idx (when (and vector-like? (#{:assoc :assoc!} action))
                    (if (< 900 x)
                      (count coll-a)
                      (int (* (count coll-a) (/ x 1e3)))))
              [action x y :as act] (if idx
                                     [action idx y]
                                     act)
              f (case action
                  :cons #(cons x %)
                  :rest rest
                  :map-id #(map identity %)
                  :seq seq
                  :into [#(into (empty orig-a) %) #(into (empty orig-b) %)]
                  :persistent! persistent!
                  :transient transient
                  :pop #(if (empty? %) % (pop %))
                  :pop! #(if (= 0 (count %)) % (pop! %))
                  :conj #(conj % x)
                  :conj! #(conj! % x)
                  :disj #(disj % x)
                  :disj! #(disj! % x)
                  :assoc #(assoc % x y)
                  :assoc! #(assoc! % x y)
                  :dissoc #(dissoc % x)
                  :dissoc! #(dissoc! % x))
              f-a (if (vector? f) (first f) f)
              f-b (if (vector? f) (second f) f)]

          [(f-a coll-a)
           (f-b coll-b)
           (conj prev-actions act)]))
      [coll-a coll-b []]
      (apply concat actions))))

;;;

;; indirection for 1.4 compatibility
;; (def reduced* (ns-resolve 'clojure.core 'reduced)) ;; CLJC

(defn assert-equivalent-collections
  [a b]
  (is (= (count a) (count b) #?@(:clj [(.size a) (.size b)])))
  (is (= a b))
  (is (= b a))
  (is (= (hash a) (hash b)))
  #?@(:clj [(is (.equals ^Object a b))
            (is (.equals ^Object b a))
            (is (= (.hashCode ^Object a) (.hashCode ^Object b)))])
  (is (= a b
         (into (empty a) a)
         (into (empty b) b)
         (into (empty a) b)
         (into (empty b) a)))
  (is (= (into (empty a) (take 1 a))
         (reduce #(reduced (conj %1 %2)) (empty a) a)))
  (is (= (into (empty b) (take 1 b))
         (reduce #(reduced (conj %1 %2)) (empty b) b))))

(defn- meta-map [s]
  (->> s (map #(vector % (meta %))) (into {})))

(defn assert-equivalent-vectors [a b]
  (assert-equivalent-collections a b)
  (is (= (first a) (first b)))
  (is (= (map #(nth a %) (range (count a)))
         (map #(nth b %) (range (count b)))))
  (is (= (map #(a %) (range (count a)))
         (map #(b %) (range (count b)))))
  (is (= (map #(get a %) (range (count a)))
         (map #(get b %) (range (count b)))))
  #?(:clj (is (= (map #(.get ^List a %) (range (count a)))
                 (map #(.get ^List b %) (range (count b))))))
  (is (= 0 (compare a b))))

(defn assert-equivalent-sets [a b]
  (assert-equivalent-collections a b)
  (is (= (set (map #(a %) a))
         (set (map #(b %) b))))
  (is (= (meta-map a) (meta-map b)))
  (is (and (every? #(contains? a %) b)
           (every? #(contains? b %) a))))

(defn assert-equivalent-maps [a b]
  (assert-equivalent-collections a b)
  (is (= (set (keys a)) (set (keys b))))
  (let [ks (keys a)]
    (is (= (map #(get a %) ks)
           (map #(get b %) ks)))
    (is (= (map #(a %) ks)
           (map #(b %) ks)))
    (is (= (map #(.get ^Map a %) ks)
           (map #(.get ^Map b %) ks))))
  (is (and (every? #(= (key %) (first %)) a)
           (every? #(= (key %) (first %)) b)))
  (is (= (meta-map (keys a)) (meta-map (keys b))))
  (is (every? #(= (meta (a %)) (meta (b %))) (keys a)))
  (is (every? #(= (val %) (a (key %)) (b (key %))) a)))

;;;

(defn pr-meta [v]
  (if-let [m (meta v)]
    `(with-meta ~v ~m)
    v))

(defn describe-action [[f & rst]]
  (case f
    :cons (list '->> (list* 'cons (map pr-meta rst)))
    :into '(into (empty coll))
    (if (empty? rst)
      (symbol (name f))
      (list*
        (symbol (name f))
        (map pr-meta rst)))))

(defn actions->str [actions]
  (->> actions
       (apply concat)
       (map describe-action)
       (list* '-> 'coll)
       pr-str))

(defmethod ct/report #?(:clj ::chuck/shrunk :cljs [::ct/default ::chuck/shrunk]) [m]
  (newline)
  (println "Tests failed:\n"
           "\n    seed =" (:seed m)
           "\n actions =" (-> m :shrunk :smallest first (get 'actions) actions->str)))

;;

(defn assert-vector-like
  "Asserts that the given empty collection empty-coll behaves like a
  vector.

  This is a function intended only to be called from within a
  clojure.test/deftest form, or some function called from within one.
  Its return value is useless -- when called within a deftest form, it
  will perform the expected side effects to update the number of
  passed and/or failed tests.

  num-tests-or-options is an integer number of tests to run, default
  1000 if not specified.  It can optionally instead be a map of
  options, as expected and documented by the test.chuck/checking
  macro (see its documentation for details).

  element-generator is a test.check generator that will be used to
  generate elements to add to the vectors.

  The last argument is an optional map, with these keys:

  * :base - value is an empty vector used as the baseline of
    comparison for correct behavior, default is a Clojure
    PersistentVector [].

  * :ordered? - value is a boolean. If true, then the randomly
    generated sequences of actions to test will include those generated
    by the return value of (seq-actions element-generator).  Default is
    true."
  ([empty-coll element-generator]
     (assert-vector-like 1000 empty-coll element-generator nil))
  ([num-tests-or-options empty-coll element-generator]
     (assert-vector-like num-tests-or-options empty-coll element-generator nil))
  ([num-tests-or-options empty-coll element-generator
    {:keys [base ordered?]
     :or {ordered? true
          base []}}]
   (chuck/checking "vector-like" num-tests-or-options
    [actions (gen-vector-actions element-generator (transient? empty-coll) ordered?)]
    (let [[a b actions] (build-collections empty-coll base true actions)]
      (assert-equivalent-vectors a b)))));)

(defn assert-set-like
  "Asserts that the given empty collection empty-coll behaves like a
  set.

  This is a function intended only to be called from within a
  clojure.test/deftest form, or some function called from within one.
  Its return value is useless -- when called within a deftest form, it
  will perform the expected side effects to update the number of
  passed and/or failed tests.

  num-tests-or-options is an integer number of tests to run, default
  1000 if not specified.  It can optionally instead be a map of
  options, as expected and documented by the test.chuck/checking
  macro (see its documentation for details).

  element-generator is a test.check generator that will be used to
  generate elements to add to the sets.

  The last argument is an optional map, with these keys:

  * :base - value is an empty set used as the baseline of
    comparison for correct behavior, default is a Clojure
    PersistentHashSet #{}.

  * :ordered? - value is a boolean. If true, then the randomly
    generated sequences of actions to test will include those generated
    by the return value of (seq-actions element-generator).  Should only
    be true if the set you wish to test is ordered, and if you do this,
    the value of :base should also be an ordered set with the same
    sorting order, e.g. the return value of sorted-set or sorted-set-by.
    Default is false."
  ([empty-coll element-generator]
     (assert-set-like 1000 empty-coll element-generator nil))
  ([num-tests-or-options empty-coll element-generator]
     (assert-set-like num-tests-or-options empty-coll element-generator nil))
  ([num-tests-or-options empty-coll element-generator
    {:keys [base ordered?]
     :or {ordered? false
          base #{}}}]
   (chuck/checking "set-like" num-tests-or-options
    [actions (gen-set-actions element-generator (transient? empty-coll) ordered?)]
    (let [[a b actions] (build-collections empty-coll base false actions)]
      (assert-equivalent-sets a b)))))

(defn assert-map-like
  "Asserts that the given empty collection empty-coll behaves like a
  map.

  This is a function intended only to be called from within a
  clojure.test/deftest form, or some function called from within one.
  Its return value is useless -- when called within a deftest form, it
  will perform the expected side effects to update the number of
  passed and/or failed tests.

  num-tests-or-options is an integer number of tests to run, default
  1000 if not specified.  It can optionally instead be a map of
  options, as expected and documented by the test.chuck/checking
  macro (see its documentation for details).

  key-generator / value-generator is a test.check generator that will
  be used to generate keys / values to add to the maps.

  The last argument is an optional map, with these keys:

  * :base - value is an empty map used as the baseline of
    comparison for correct behavior, default is a Clojure
    persistent map {}.

  * :ordered? - value is a boolean. If true, then the randomly
    generated sequences of actions to test will include those generated
    by the return value of (seq-actions element-generator).  Should only
    be true if the map you wish to test is ordered, and if you do this,
    the value of :base should also be an ordered map with the same
    sorting order, e.g. the return value of sorted-map or sorted-map-by.
    Default is false."
  ([empty-coll key-generator value-generator]
     (assert-map-like 1000 empty-coll key-generator value-generator nil))
  ([num-tests-or-options empty-coll key-generator value-generator]
     (assert-map-like num-tests-or-options empty-coll key-generator value-generator nil))
  ([num-tests-or-options empty-coll key-generator value-generator
    {:keys [base ordered?]
     :or {ordered? false
          base {}}}]
   (chuck/checking "map-like" num-tests-or-options
    [actions (gen-map-actions key-generator value-generator (transient? empty-coll) ordered?)]
    (let [[a b actions] (build-collections empty-coll base false actions)]
      (assert-equivalent-maps a b)))))
