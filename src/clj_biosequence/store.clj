(ns clj-biosequence.store
  (:require [clj-biosequence.write :as wr]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.conversion :as con]
            [monger.db :as mdb]
            [monger.util :as mu])
  (:import [com.mongodb MongoOptions ServerAddress WriteConcern]
           [org.bson.types ObjectId]))

(declare save-list store-read get-record)

;; interface

(defprotocol storeCollectionIO
  (mongo-save-file [this project name]
    "Saves a biosequenceFile to a mongoDB for random access."))

(defprotocol storeCollectionAccess
  (collection-seq [this]
    "Returns a lazy list of entries in a biosequenceCollection."))

;; project

(defrecord mongoProject [name])

(defrecord biosequenceCollection [name pname type]

  storeCollectionAccess

  (collection-seq [this]
    (get-record this :element "sequence")))

(defmethod print-method clj_biosequence.store.biosequenceCollection
  [this ^java.io.Writer w]
  (wr/print-tagged this w))

(defn init-biosequence-collection
  [name pname type]
  (->biosequenceCollection name pname type))

;; functions

(defn mongo-connect
  "Connects to the default mongo database server."
  []
  (mg/connect!))

(defn mongo-disconnect
  []
  "Disconnects from the default mongo database server."
  (mg/disconnect!))

(defn init-project
  "Returns a mongoProject for storing sequence collections. Used for
  accessing existing projects or initialising new ones."
  [name]
  (mg/use-db! "clj-projects")
  (if (not (mc/exists? "sequences"))
    (let [p (mc/insert-and-return "sequences" {:pname name :project "t" :started (new java.util.Date)})]
      (mc/ensure-index "sequences"
                       (array-map :acc 1 :batch_id 1 :_id 1)
                       {:unique true})
      (mc/ensure-index "sequences"
                       (array-map :pname 1 :iname 1 :coll 1 :_id 1)
                       {:sparse true})
      (assoc (->mongoProject name) :started (:started p)))
    (let [p (first (mc/find-maps "sequences" {:pname name :project "t"}))]
      (if p
        (assoc (->mongoProject name) :started (:started p))
        (assoc (->mongoProject name) :started
          (mc/insert-and-return "sequences" {:pname name :project "t" :started (new java.util.Date)}))))))

(defn list-projects
  "Returns a set of projects on the server."
  []
  (mg/use-db! "clj-projects")
  (distinct (map :pname (mc/find-maps "sequences" {:project "t"} [:pname]))))

(defn drop-project
  "Takes a mongoProject and drops it from the database."
  [project]
  (mg/use-db! "clj-projects")
  (mc/remove "sequences" {:pname (:name project)}))

(defn get-collections
  "Returns a list of collections in a project."
  ([project] (get-collections project nil))
  ([project collection]
     (mg/use-db! "clj-projects")
     (let [c (if collection {:iname collection} {})]
       (->> (mc/find-maps "sequences"
                          (merge {:pname (:name project) :coll "t"} c)
                          [:src])
            (map #(wr/bs-read (:src %)))
            (map #(dissoc % :batch_id))
            distinct))))

(defn list-collections
  "Takes a mongoProject and returns a hash-map of collection names and
  types in the project."
  [project]
  (mg/use-db! "clj-projects")
  (->> (get-collections project)
       (map (fn [{n :name t :type}] {n t}))))

(defn drop-collection
  "takes a collection object and drops it from the database."
  [collection]
  (mg/use-db! "clj-projects")
  (mc/remove "sequences"
             {:pname (:pname collection) :iname (:name collection)}))

(defn get-record
  ([collection value] (get-record collection :acc value))
  ([collection key value & kv]
     (mg/use-db! "clj-projects")
     (map store-read (mc/find-maps "sequences" (merge {key value
                                                       :pname (:pname collection)
                                                       :iname (:name collection)}
                                                      (apply hash-map kv))))))

(defn save-list
  "Takes a list of hash-maps for insertion into a mongoDB and a
  collection object and inserts all members of the list."
  [l i]
  (mg/use-db! "clj-projects")
  (let [u (mu/random-uuid)]
    (try
      (dorun (pmap #(mc/insert-batch "sequences"
                                     %
                                     WriteConcern/JOURNAL_SAFE)
                   (partition-all 1000
                                  (cons
                                   {:_id (ObjectId.)
                                    :src (pr-str (assoc i :batch_id u))
                                    :pname (:pname i) :iname (:name i)
                                    :coll "t" :batch_id u}
                                   (pmap #(merge {:_id (ObjectId.) :batch_id u
                                                  :pname (:pname i) :iname (:name i)
                                                  :element "sequence"} %)
                                         l)))))
      (catch Exception e
        (mc/remove "sequences" {:batch_id u})
        (throw e)))
    (assoc i :batch_id u)))

;; utilities

(defn- store-read
  [h]
  (if-let [o (wr/bs-read (:src h))]
    (if (string? o)
      o
      (merge o (dissoc h :src)))))

