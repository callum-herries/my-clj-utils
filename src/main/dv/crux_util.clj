(ns dv.crux-util
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn >defn- >def | => ?]]
    [crux.api :as crux]
    [crux.backup :as backup]
    [dv.crux-node :refer [crux-node]]
    [dv.fulcro-util-common :as fu]
    [taoensso.timbre :as log])
  (:import [java.util Date]
           [java.util UUID]
           [crux.api ICruxDatasource ICruxAPI]))

(defn id?
  "True if keyword or uuid"
  [id]
  (or (keyword? id) (uuid? id)))

(defn crux-node? [n] (instance? ICruxAPI n))
(comment (crux-node? crux-node))

;; show all attributes in the node
(comment (crux/attribute-stats crux-node))

(comment
  (backup/backup {:backup-dir "crux-backup"} crux-node)
  (backup/restore {:db-dir "restore-crux-store/db" :event-log-dir "restore-crux-store/event-log" :backup-dir "crux-backup"}))

(defn db? [x] (.isInstance ICruxDatasource x))

(defn ->db [node-or-db]
  (cond
    (crux-node? node-or-db)
    (crux/db node-or-db)
    (db? node-or-db) node-or-db
    :else (throw (Exception. (str "Unsopported value passed to ->db: " (pr-str node-or-db))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Writing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn put-async
  ([data] (put-async crux-node data))
  ([crux-node data]
   (let [data (dissoc data :db/created-at :db/updated-at)]
     (log/info "Transacting data: " data)
     (crux/submit-tx crux-node [[:crux.tx/put data]]))))

(defn put
  ([data] (put crux-node data))
  ([crux-node doc]
   (crux/await-tx crux-node (put-async crux-node doc))))

(s/def ::crux-doc (s/keys :req [:crux.db/id]))
(s/def ::crux-tx-return (s/keys :req [:crux.tx/tx-id :crux.tx/tx-time]))
(s/def ::vec-of-docs (s/coll-of ::crux-doc :type vector?))

(>defn put-all-async
  "Uses crux put transaction to add a vector of documents to a specified node."
  [crux-node docs]
  [crux-node? ::vec-of-docs => ::crux-tx-return]
  (crux/submit-tx crux-node
    (mapv #(vector :crux.tx/put (dissoc % :db/created-at :db/updated-at)) docs)))

(comment
  (put-all-async crux-node [{:crux.db/id :first-test :name :hi}]))

(>defn put-all
  ([docs]
   [::vec-of-docs => ::crux-tx-return]
   (put-all crux-node docs))
  ([crux-node docs]
   [crux-node? ::vec-of-docs => ::crux-tx-return]
   (crux/await-tx crux-node (put-all-async crux-node docs))))
(comment
  (put-all crux-node [{:crux.db/id :first-test :name :hi}]))

(defn assoc-crux-id
  "kw field and map of data with a property for that kw to use as the crux.db/id"
  [field m]
  (assoc m :crux.db/id (get m field)))

(defn insert-entity
  "Invokes put on the passed in map after associng crux.db/id onto the map.
  uses id-kw - keyword to get an id val for this entity to add crux.db/id to insert"
  ([id-kw e]
   (insert-entity crux-node id-kw e))
  ([crux-node id-kw e]
   (put crux-node (assoc-crux-id id-kw e))))

(defn update-entity
  ([entity-id field f]
   (update-entity crux-node entity-id field f))
  ([crux-node entity-id field f]
   (let [ent     (crux/entity (crux/db crux-node) entity-id)
         new-val (update ent field f)]
     (put crux-node new-val))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity reading + query
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn q
  "Execute a query against the provided crux database or after calling 'db' on the crux-node."
  ([query]
   (q crux-node query))
  ([crux-node query] (crux/q (->db crux-node) query)))

(defn field-kws->clauses
  "returns: [[e :field1 field1] [e :field2 field2]]"
  [fields]
  (mapv vec (partition 3 (interleave (repeat 'e) fields (mapv (comp symbol name) fields)))))
(comment (field-kws->clauses task-fields))

(defn crux-select
  "
  fields - collection of properties to find all documents that have them.

  returns a vector of maps of the fields to their corresponding values.
  fields is a vector of keywords
  (crux-select [:task/id :task/name :task/description])
  =>
  makes a query find ['id 'name 'description] :where ['e :task/id 'id] etc

  "
  ([fields] (crux-select crux-node fields))
  ([crux-node fields]
   (assert (every? qualified-keyword? fields))
   (let [clauses     (field-kws->clauses fields)
         field-names (mapv (comp symbol name) fields)
         query       {:find field-names :where clauses}
         tuples      (q crux-node query)]
     (mapv
       (fn [t]
         (apply hash-map (interleave fields t)))
       tuples))))

(defn entity-id-for-prop
  ([v] (entity-id-for-prop crux-node v))
  ([db [attr value]]
   (ffirst (crux/q (->db db)
             {:find  ['?e]
              :where [['?e attr value]]
              :args  [{'attr attr 'value value}]}))))

(comment (entity-id-for-prop crux-node [:val "1"]))

(defn entity-with-prop
  "eid - [:kw val]
  Get an entity that has a property with value. copied from crux site
  (entity-with-prop [:email \"myaddress@addres.com\"])"
  ([eid] (entity-with-prop crux-node eid))
  ([crux-node eid]
   (when eid
     (let [db (->db crux-node)]
       (if (vector? eid)
         (let [[attr value] eid]
           (recur crux-node (entity-id-for-prop db [attr value])))
         (crux/entity db eid))))))

(defn entity-id-with-prop
  [eid] (some-> (entity-with-prop eid) :crux.db/id))

(comment
  (crux.api/entity (crux/db crux-node) "c28ca34d-bd4c-4036-8db9-8b1bc7ed2c7b")
  (put {:crux.db/id :test1 :val "1"})
  (entity-with-prop crux-node [:val "1"])
  (entity-id-for-prop (crux/db crux-node) [:val "1"])
  (crux/entity (crux/db crux-node) :test1)
  (keys (crux/db crux-node))
  )

(>defn all-entities-exist?
  "Takes collection of prop to value: [[:task/id :id]]
  checks that they return an entity and not nil"
  [coll]
  [(s/coll-of (s/or :id id? :ident ::ident)) => boolean?]
  (every? entity-id-with-prop coll))

(defn entity
  ([entity-id] (entity crux-node (if (fu/ref? entity-id) (second entity-id) entity-id)))
  ([crux-node entity-id]
   (crux/entity (->db crux-node) (if (fu/ref? entity-id) (second entity-id) entity-id))))

(s/def ::tx-timestamps (? (s/map-of #{:db/updated-at :db/created-at} (? inst?))))

(defn get-doc-created-at [crux-node id]
  (with-open [history (crux/open-entity-history (->db crux-node) id
                        :asc {:with-docs? false})]
    (-> (iterator-seq history) first :crux.tx/tx-time)))

(defn get-doc-updated-at [crux-node id]
  (with-open [history (crux/open-entity-history (->db crux-node) id
                        :desc {:with-docs? false})]
    (-> (iterator-seq history) first :crux.tx/tx-time)))

(>defn get-timestamps
  "use history api to get
  last update time and creation time for an entity.
  for an entity: nilable: {:db/updated-at last tx-time :db/created-at first tx-time}"
  ([id]
   [id? => ::tx-timestamps]
   (get-timestamps crux-node id))
  ([crux-node id]
   [crux-node? id? => ::tx-timestamps]
   (let [created-at (get-doc-created-at crux-node id)
         updated-at (get-doc-updated-at crux-node id)]
     (when (and updated-at created-at)
       {:db/updated-at updated-at :db/created-at created-at}))))

(comment
  (crux-select crux-node [:user/id])
  (get-timestamps #uuid"011391e2-1c39-4f71-8175-3fe098289d41")
  (get-timestamps #uuid"c6836add-b0d7-4ddc-83d1-fb6ed95b8d10")
  (get-timestamps crux-node #uuid "e0fdda94-5cfe-4062-bf2a-1cdb2521e4f9"))

(defn history
  ([id] (history crux-node id :desc))
  ([id sort-order] (history crux-node id sort-order))
  ([crux-node id sort-order]
   (crux/entity-history (->db crux-node) id sort-order
     {:with-docs? true}))
  ([crux-node id sort-order opts]
   (crux/entity-history (->db crux-node) id sort-order opts)))

(comment
  (crux-select crux-node [:user/id])
  (history #uuid"c6836add-b0d7-4ddc-83d1-fb6ed95b8d10")
  (history crux-node #uuid"c6836add-b0d7-4ddc-83d1-fb6ed95b8d10" :asc {:with-docs? false})
  )

(defn domain-entity
  "Invokes crux/entity and adds db/created-at and db/updated-at to the return value."
  ([id]
   (domain-entity crux-node (if (fu/ref? id) (second id) id)))
  ([crux-node id]
   (merge (entity crux-node (if (fu/ref? id) (second id) id))
     (get-timestamps crux-node id))))

(comment
  (domain-entity #uuid "e0fdda94-5cfe-4062-bf2a-1cdb2521e4f9"))


;; Ancestor and parent query helpers

(defn get-parent
  "Returns nilable ID of the parent document who has the child with the given ident value under the 'child-attr'
  attribute.
  (get-parent :goal/subgoals [:goal/id #uuid '...']
  ident-val is either an ident or an id"
  [child-attr ident-val]

  ;; todo  this logic needs to be udpated to work with keyword refs and map ref - the types of keys crux supports
  ;; besides idents and uuids
  (if (fu/ident? ident-val)
    (ffirst
      (q crux-node
        {:find  '[parent-id]
         :where [['parent-id child-attr 'ident-val]]
         :args  [{'ident-val ident-val}]}))
    (ffirst
      (q crux-node
        {:find  '[parent-id]
         :where [['parent-id child-attr 'ident-val]
                 '[(second ident-val) id-val*]
                 '[(= id-val* id-val)]]
         :args  [{'id-val ident-val}]}))))

(comment
  (get-parent :goal/subgoals #uuid "240b8e42-b8c2-41d6-b7de-74acce2e9c7e")
  (get-parent :goal/subgoals [:goal/id #uuid "db08f619-e789-4dd1-8a9d-59991346bf6b"])
  )

(defn get-ancestors
  "Repeatedly invokes 'get-parent' constructing an ordered vector of ids of all the parents to the root document.
  (get-ancestors :goal/subgoals dm/goal-ident #uuid \"240b8e42-b8c2-41d6-b7de-74acce2e9c7e\")"
  [child-attr ident-fn id-val]
  (loop [ancestors () parent-id (get-parent child-attr (ident-fn id-val))]
    (if parent-id
      (recur (conj ancestors parent-id) (get-parent child-attr (ident-fn parent-id)))
      (vec ancestors))))

(comment
  (get-ancestors :goal/subgoals dm/goal-ident #uuid "240b8e42-b8c2-41d6-b7de-74acce2e9c7e")
  (get-ancestors :goal/subgoals dm/goal-ident #uuid "db08f619-e789-4dd1-8a9d-59991346bf6b")
  )

(comment
  (get-parent :goal/subgoals
    ;; top level
    ;[:goal/id #uuid "240b8e42-b8c2-41d6-b7de-74acce2e9c7e"]

    ;; nested subgoal
    [:goal/id #uuid "db08f619-e789-4dd1-8a9d-59991346bf6b"]
    ))

;; Pathom helpers

(defn join-ref
  "Convert an ident or a collection of idents into a map or a collection of maps as needed by pathom to traverse
  a join relationship.

  v - ident or coll of idents
  kw - keyword of the prop that has the id

  input is one of:

  - ident [:task/id 'id'] => {:task/id 'id'}
  - coll of idents [[:task/id 'id']] => [{:task/id 'id'}]

  Takes field and either one id or a collection of ids and returns
  one hash-map for one id, or collection of hash-maps for coll of ids.
  i.e. - Turn a collection of ids into maps so pathom can keep walking the graph."
  ([v]
   ;; [:goal/id "id"]
   ;; [[:goal/id "id"] [:goal/id "id2"]]
   (cond
     (fu/ref? v)
     (apply hash-map v)

     (fu/coll-of-idents? v)
     (mapv #(apply hash-map %) v)

     :other
     (throw (Exception. (str "crux-util, unsupported type passed to join-ref: " (pr-str v))))))

  ([kw v]
   (cond
     (fu/ref? v)
     (apply hash-map v)

     (coll? v)
     (cond
       (every? #(and (map? %) (contains? % kw)) v)
       (mapv #(select-keys % [kw]) v)

       (every? fu/ref? v)
       (mapv #(apply hash-map %) v)

       :else (when-not (every? fu/ref? v)
               (throw (Exception. (str "err1 crux-util, join-ref passed a non-ident value for field: " (pr-str kw) " value: " (pr-str v))))))

     (id? v)
     {kw v}

     (nil? v)
     nil

     :else
     (throw (Exception. (str "err2 crux-util, join-ref passed a non-id property for field: " (pr-str kw) " value: " (pr-str v)))))))

(comment

  (mapv #(apply hash-map %) [[:task/id #uuid"ec0c6600-5f33-4d2d-844e-7da15586edcb"]])
  (join-ref [:task/id #uuid"ec0c6600-5f33-4d2d-844e-7da15586edcb"])
  (join-ref [[:task/id #uuid"ec0c6600-5f33-4d2d-844e-7da15586edcb"] [:task/id #uuid"ec0c6600-5f33-4d2d-844e-7da15586edcb"]])
  (join-ref :task/id [#uuid"ec0c6600-5f33-4d2d-844e-7da15586edcb"])
  (join-ref :task/id #{#uuid"ec0c6600-5f33-4d2d-844e-7da15586edcb"})
  (join-ref :task/id #uuid"ec0c6600-5f33-4d2d-844e-7da15586edcb"))

(>defn pathom-expand-entity
  "Returns an entity from crux with modifications.
  Fields is vec of tuples kw, updates from plain ids as come out of db into maps
  with the field name as key and val as the id itself, as required for pathom
  to process a join."
  [field-tuples id]
  [(s/coll-of (s/tuple qualified-keyword? qualified-keyword?) :type vector?) (s/or :id id? :ident fu/ref?) => (? map?)]
  (if-let [ent (domain-entity id)]
    (reduce
      (fn [ent [prop id-kw]]
        (if (contains? ent prop)
          (update ent prop #(join-ref id-kw %))
          ent))
      ent
      field-tuples)))

(>defn pathom-join
  [field-tuples entity]
  [(s/coll-of (s/tuple qualified-keyword? qualified-keyword?) :type vector?) map? => map?]
  (reduce
    (fn [ent [prop id-kw]]
      (update ent prop #(join-ref id-kw %)))
    entity
    field-tuples))

(comment
  (pathom-expand-entity
    [[:user/tasks :task/id]
     [:user/habits :habit/id]]
    #uuid"c6836add-b0d7-4ddc-83d1-fb6ed95b8d10")
  )

(defn entity-at
  [entity-id valid-time]
  (crux/entity (crux/db crux-node valid-time) entity-id))

(>defn read-merge-entity
  "Update an existing entity using the given map, deals with fetching the entity first. does not write"
  ([id-attr new-attrs]
   [keyword? map? => map?]
   (read-merge-entity crux-node id-attr new-attrs))

  ([crux-node id-attr new-attrs]
   [some? keyword? map? => map?]
   (let [entity-id         (id-attr new-attrs)
         entity-prev-value (entity crux-node entity-id)]
     (merge entity-prev-value new-attrs {:crux.db/id entity-id}))))

(comment
  (read-merge-entity :user/id {:user/id #uuid "2eb987f0-d580-4545-8b26-671ac8083260"}))


(defn mk-get-id-from-coll
  "Unique id prop of item in a collection of another entity under the f-kw prop
  ex: :task/id :task/description, returns a fn that takes a collection of ids
  and a value to find for each of the entities."
  [id-kw f-kw]
  (fn get-id-from-coll*
    [col v]
    (when (seq col)
      (ffirst
        (q
          {:find  '[t]
           :where [['t id-kw 'id]
                   ['t f-kw v]]
           :args  (mapv
                    #(hash-map 'id (fu/ident->id %)) col)})))))

(comment
  (def get-task-by-description (mk-get-id-from-coll :task/id :task/description))
  (get-task-by-description [] "Something to do"))

(defmacro gen-make-db-entity
  "Assoces crux id"
  [name-sym spec]
  (let [nm      (name spec)
        make-fn (symbol (str "make-" nm))
        id-kw   (keyword nm "id")
        props   (gensym "props")]
    `(>defn ~name-sym
       ~(str "Make a " nm " to insert into crux")
       [~props]
       [map? ~'=> ~spec]
       (assoc-crux-id ~id-kw (~make-fn ~props)))))

(comment
  (macroexpand-1 '(gen-make-db-entity make-db-task ::task))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Update entity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn merge-entity
  "Assumes entity exists are you're creating a new one (you need to pass crux.db/id if so
  or use merge-domain-entity).
  Update an existing entity using the given map, deals with fetching the entity first."
  ([entity-id new-attrs]
   {:pre [(or (vector? entity-id) (id? entity-id)) (map? new-attrs)]}
   (merge-entity crux-node entity-id new-attrs (Date.)))

  ([crux-node entity-id new-attrs]
   {:pre [(or (vector? entity-id) (id? entity-id)) (map? new-attrs)]}
   (merge-entity crux-node entity-id new-attrs (Date.)))

  ([crux-node entity-id new-attrs valid-time]
   {:pre [(or (vector? entity-id) (id? entity-id)) (map? new-attrs)]}
   (let [entity-prev-value (entity-with-prop crux-node entity-id)
         _                 (log/info "merge prev valu: " entity-prev-value)
         new-val           (merge entity-prev-value new-attrs)]
     (log/info "merge entity new val: " new-val)
     (crux/submit-tx crux-node [[:crux.tx/put new-val valid-time]]))))

(comment
  (merge-entity (:task/id %) (assoc %)))

(defn merge-domain-entity
  "field where unique key is and new data `m`"
  [id-kw m]
  (let [id (id-kw m)]
    (if id
      (merge-entity id (assoc-crux-id id-kw m))
      (throw (Exception. (str "Missing id field: " id-kw " in map: " m))))))

(defn ensure-key [k]
  (if (id? k) k (UUID/fromString k)))


(defn delete-all
  "Synchronously deletes all the passed ids (can be either idents or ids)."
  [crux-node ids]
  (let [ids (mapv #(:crux.db/id (entity-with-prop %)) ids)]
    (log/info "Deleting entities with keys: " (pr-str ids))
    (crux/await-tx
      crux-node
      (crux/submit-tx crux-node
        (mapv (fn [i] [:crux.tx/delete (ensure-key i)]) ids)))))

(defn delete
  "Key is either [:some-prop \"value\"]
  or a value for crux.db/id"
  ([key] (delete crux-node key))
  ([crux-node key]
   (let [key (:crux.db/id (entity-with-prop key))]
     (log/info "Deleting entity with key: " (pr-str key))
     (crux/await-tx
       crux-node
       (crux/submit-tx crux-node [[:crux.tx/delete (ensure-key key)]])))))

(defn delete-entities-with-field
  "For all entities with the field `field` deletes them."
  [field]
  (dorun (map
           #(delete (field %))
           (crux-select crux-node [field]))))

;; todo could make this support ids or idents

(>defn- get-nested-ids*
  "Helper for nested ids"
  [property input output]
  [qualified-keyword? (s/coll-of fu/ref? :kind vector?) (s/coll-of uuid? :kind vector?)
   =>
   (s/coll-of uuid? :kind vector?)]
  (if (empty? input)
    output
    (let [entity-id   (second (first input))
          remaining   (rest input)
          entity      (entity entity-id)
          subentities (get entity property)]

      (if (not (empty? subentities))
        (recur property
          (vec (concat remaining subentities)) ;; new input
          (into (mapv second subentities) output)) ;; new output
        (recur property remaining output)))))

(>defn get-nested-ids
  "Return a depth first ordered vector of ids for all entities for the idents passed in.

  - property: keyword for nested recursive property
  - input: ident or a vector of idents to start at

  - returns: vector of ids
  "
  [property input]
  [qualified-keyword?
   (s/or
     :coll (s/coll-of fu/ref? :kind vector?)
     :id fu/ref?) => (s/coll-of uuid? :kind vector?)]
  (get-nested-ids* property (if (fu/ref? input) [input] input) []))

(comment
  (get-nested-ids :ad-hoc-task/subtasks [[:ad-hoc-task/id #uuid "68203a9c-046c-432b-adb5-ff26873f9ab2"]] []))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data migration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; 1. query for all documents with old property
; 2. Write new document

(defn migrate-attr
  "Finds all documents with `old-attr`, invokes `xf` on the value of old-attr and
  assoces it at `new-attr`"

  [old-attr new-attr xf]
  (->> (crux-select [:crux.db/id old-attr])
    (into []
      (map (comp
             #(dissoc % old-attr)
             ;; todo just call xf with the whole doc as an alternative
             #(assoc % new-attr (xf (old-attr %)))
             entity
             :crux.db/id)))
    put-all))

;(comment (migrate-attr :habit/start-at :habit/starts-on tu/->date))
