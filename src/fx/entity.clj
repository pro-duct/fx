(ns fx.entity
  (:require
   [integrant.core :as ig]
   [malli.core :as m]
   [malli.error :as me]
   [malli.registry :as mr]
   [medley.core :as mdl]
   [fx.utils.common :as uc])
  (:import
   [clojure.lang MapEntry]))


;; =============================================================================
;; Entity spec parser
;; =============================================================================

(def EntityRawSpec
  "Recursive malli schema to parse provided by user entities schemas e.g.
   [:spec {:table \"client\"}
     [:id {:primary-key? true} uuid?]
     [:name [:string {:max 250}]]
     [:user {:one-to-many? true} :fx.entity-test/user]]"
  [:schema
   {:registry
    {::entity [:catn
               [:entity :keyword]
               [:properties [:? map?]]
               [:fields [:* [:schema [:ref ::field]]]]]
     ::field  [:catn
               [:name :keyword]
               [:properties [:? [:map-of :keyword :any]]]
               [:type ::type]]
     ::type   [:altn
               [:fn-schema fn?]
               [:type simple-keyword?]
               [:type-with-props [:tuple :keyword map?]]
               [:entity-ref-key :qualified-keyword]]}}
   ::entity])


(def entity-raw-spec?
  (m/-simple-schema
   {:type :entity/raw-spec
    :pred #(m/validate EntityRawSpec %)}))


(def EntitySpec
  "Recursive malli schema to unparse provided by user entities schemas to internal schema representation"
  [:schema
   {:registry
    {::entity [:catn
               [:entity :keyword]
               [:properties [:? map?]]
               [:fields [:* [:schema [:ref ::field]]]]]
     ::field  [:catn
               [:name :keyword]
               [:properties [:? [:map-of :keyword :any]]]
               [:type ::type]]
     ::type   [:altn
               [:fn-schema fn?]
               [:type simple-keyword?]
               [:ref-type [:tuple
                           [:= :entity-ref]
                           [:map
                            [:entity :qualified-keyword]
                            [:entity-ref :qualified-keyword]]]]
               [:type-with-props [:tuple :keyword map?]]]}}
   ::entity])


(def entity-spec?
  (m/-simple-schema
   {:type :entity/spec
    :pred #(m/validate EntitySpec %)}))


;; =============================================================================
;; Entities registry
;; =============================================================================

(declare entities-registry)


(def registry*
  "Atom to hold malli schemas"
  (atom (merge
         (m/default-schemas)
         {:spec       (m/-map-schema)
          :entity-ref (m/-simple-schema
                       (fn [{:keys [entity-ref]} _]
                         {:type :entity-ref
                          :pred #(m/validate entity-ref % {:registry entities-registry})}))})))


(def entities-registry
  "Mutable malli registry to hold all defined by user entities  + some utility schemas"
  (mr/mutable-registry registry*))


(defn ->entity-ref
  "Returns the entity ref type for given entity type e.g.
   :my-cool/entity -> :my-cool/entity-ref"
  [entity-type]
  (let [entity-ns       (namespace entity-type)
        entity-name     (name entity-type)
        entity-ref-name (str entity-name "-ref")]
    (keyword entity-ns entity-ref-name)))

(m/=> ->entity-ref
  [:=> [:cat :qualified-keyword]
   :qualified-keyword])


(defn ->entity-ref-schema
  "Will return a simplified malli schema for a given entity
   Basically wil try to identify a type of primary key field"
  [schema]
  (let [[pkey pkey-val] (->> (m/entries schema {:registry entities-registry})
                             (filter (fn [[_ v]]
                                       (-> v m/properties :primary-key?)))
                             first)
        pkey-type (-> pkey-val m/children first)]
    [:or pkey-type [:map [pkey pkey-type]]]))

(m/=> ->entity-ref-schema
  [:=> [:cat entity-spec?]
   [:tuple [:= :or] :any
    [:tuple [:= :map] [:tuple :any :any]]]])


(defn register-entity-ref!
  "Adds the entity ref schema to the global registry"
  [entity-type schema]
  (swap! registry* assoc (->entity-ref entity-type) (->entity-ref-schema schema)))

(m/=> register-entity-ref!
  [:=> [:cat :qualified-keyword entity-spec?]
   :any])


(defn register-entity!
  "Adds entity and its reference schemas to the global registry"
  [entity-type schema]
  (register-entity-ref! entity-type schema)
  (swap! registry* assoc entity-type schema))

(m/=> register-entity!
  [:=> [:cat :qualified-keyword entity-spec?]
   :any])


;; =============================================================================
;; Entity implementation
;; =============================================================================

(defrecord Entity [type])


(def entity?
  (m/-simple-schema
   {:type :entity/instance
    :pred #(instance? Entity %)}))


(def schema?
  (m/-simple-schema
   {:type :entity/schema
    :pred m/schema?}))


(def entity-field-schema?
  (m/-simple-schema
   {:type :entity/field-schema
    :pred #(and (map-entry? %)
                (-> % val m/schema?))}))


(def field-schema?
  (m/-simple-schema
   {:type :entity/field-val-schema
    :pred #(= (m/type %) ::m/val)}))


;; =============================================================================
;; Entity helpers
;; =============================================================================

(def required-rel-types
  #{:one-to-one? :many-to-one?})


(def optional-rel-types
  #{:one-to-many? :many-to-many?})


(defn optional-ref?
  "Check if some reference field is optional"
  [props]
  (-> (and (some? props)
           (some props optional-rel-types))
      boolean))

(m/=> optional-ref?
  [:=> [:cat [:maybe map?]]
   :boolean])


(defn valid-entity?
  "Check if data is aligned with entity spec"
  [entity data]
  (let [entity-type (:type entity)]
    (or (m/validate entity-type data {:registry entities-registry})
        (throw (ex-info (str "Invalid data for entity " entity-type)
                        {:error (me/humanize (m/explain entity-type data {:registry entities-registry}))})))))

(m/=> valid-entity?
  [:=> [:cat entity? map?]
   :boolean])


(defn entity-fields
  "Return a list of entity fields specs (map-entries)"
  [entity]
  (let [entity-type (:type entity)
        schema      (mr/schema entities-registry entity-type)]
    (->> (m/entries schema {:registry entities-registry})
         (filter (fn [entry]
                   (if-some [props (-> entry val m/properties)]
                     (not (optional-ref? props))
                     true))))))

(m/=> entity-fields
  [:=> [:cat entity?]
   [:sequential entity-field-schema?]])


(defn entity-columns
  "Return a list of entity fields names (keywords)"
  [entity]
  (->> (entity-fields entity)
       (mapv key)))

(m/=> entity-columns
  [:=> [:cat entity?]
   [:sequential :keyword]])


(defn entity-values
  "Extract entity columns data.
   map -> table e.g.
   {:id 1 :name 'Jack'} -> [1 'Jack']"
  [entity data]
  (let [columns          (entity-columns entity)
        get-columns-vals (apply juxt columns)]
    (get-columns-vals data)))

(m/=> entity-values
  [:=> [:cat entity? map?]
   [:vector :any]])


(defn ref?
  "Check if type is a reference to another entity"
  [schema]
  (let [props (m/properties schema)]
    (->> (:entity props)
         (mr/schema entities-registry)
         some?)))

(m/=> ref?
  [:=> [:cat schema?]
   :boolean])


(defn ident-field-schema
  "Get the spec of a field which is marked as identity field"
  [entity]
  (let [entity-type (:type entity)
        schema      (mr/schema entities-registry entity-type)]
    (->> (m/entries schema {:registry entities-registry})
         (filter (fn [[_ v]]
                   (-> v m/properties :identity?)))
         first)))

(m/=> ident-field-schema
  [:=> [:cat entity?]
   entity-field-schema?])


(defn schema-type
  "Get the schema type"
  [schema]
  (m/type schema))

(m/=> schema-type
  [:=> [:cat schema?]
   :keyword])


(defn properties
  "Get the schema properties map if presented"
  [schema]
  (m/properties schema))

(m/=> properties
  [:=> [:cat schema?]
   [:maybe map?]])


(defn prop
  "Get the property from entity by key"
  [entity prop-key]
  (let [entity-type (:type entity)]
    (-> (mr/schema entities-registry entity-type)
        (m/properties {:registry entities-registry})
        (get prop-key))))

(m/=> prop
  [:=> [:cat entity? :keyword]
   :any])


(defn field-schema
  "Get the simplified definition of the field spec"
  [field-schema]
  (when-let [schema (some-> field-schema m/children first)]
    {:type  (m/type schema)
     :props (m/properties schema)}))

(m/=> field-schema
  [:=> [:cat field-schema?]
   [:map
    [:type [:or :symbol :keyword]]
    [:props [:maybe map?]]]])


(defn ref-field-prop
  "Get any property value from field spec"
  [field-schema prop-key]
  (when-let [schema (some-> field-schema m/children first)]
    (let [deps-schema (some->> (m/properties schema)
                               :entity
                               (mr/schema entities-registry))]
      (-> deps-schema
          (m/properties {:registry entities-registry})
          (get prop-key)))))

(m/=> ref-field-prop
  [:=> [:cat field-schema? :keyword]
   :any])


(defn depends-on?
  "Check if one entity depends on the other as foreign tables in SQL"
  [target dependency]
  (->> (entity-fields target)
       (filter (fn [[_ v]]
                 (let [entry-schema (-> v m/children first)]
                   (and (-> v m/properties :foreign-key?)
                        (= (:type dependency)
                           (-> entry-schema m/properties :entity))))))
       not-empty
       boolean))

(m/=> depends-on?
  [:=> [:cat entity? entity?]
   :boolean])


;; =============================================================================
;; Entity constructor
;; =============================================================================

(defn ->ref-spec-type [entity]
  (MapEntry. :ref-type [:entity-ref
                        {:entity     entity
                         :entity-ref (->entity-ref entity)}]))


(defn prepare-spec
  "Transform the user defined entity spec to something more understandable for malli.
   Original specs will cause errors due to cress spec references"
  [spec]
  (let [parsed-spec (m/parse EntityRawSpec spec)

        {:keys [fields deps]}
        (reduce (fn [{:keys [fields deps]} {:keys [properties type] :as field}]
                  (let [ref?          (= :entity-ref-key (-> type first))
                        optional-prop (:optional? properties)
                        optional-ref  (optional-ref? properties)
                        field'        (cond-> field
                                              ref?
                                              (-> (assoc :type (-> type second ->ref-spec-type))
                                                  (assoc-in [:properties :foreign-key?] true))

                                              (or optional-prop optional-ref)
                                              (-> (assoc-in [:properties :optional] true)
                                                  (mdl/dissoc-in [:properties :optional?])))]
                    {:fields (conj fields field')
                     :deps   (cond-> deps
                                     (and ref? (not optional-ref))
                                     (conj (second type)))}))
                {} (:fields parsed-spec))]
    {:deps deps
     :spec (->> (assoc parsed-spec :fields fields)
                (m/unparse EntitySpec))}))

(m/=> prepare-spec
  [:=> [:cat entity-raw-spec?]
   [:map
    [:deps [:maybe [:sequential :qualified-keyword]]]
    [:spec entity-spec?]]])


(defn create-entity
  "Constructor function for entities"
  [entity-type spec]
  (register-entity! entity-type spec)
  (->Entity entity-type))

(m/=> create-entity
  [:=> [:cat :qualified-keyword entity-spec?]
   entity?])


;; =============================================================================
;; Duct integration
;; =============================================================================

(defmethod ig/prep-key :fx/entity [entity-key raw-spec]
  (let [valid-spec? (m/validate EntityRawSpec raw-spec)]

    (when-not valid-spec?
      (let [entity-type (uc/entity-key->entity-type entity-key)]
        (throw (ex-info (str "Invalid spec schema for entity " entity-type)
                        {:error (me/humanize (m/explain EntityRawSpec raw-spec))}))))

    (let [{:keys [spec deps]} (prepare-spec raw-spec)]
      (reduce (fn [acc dep-entity]
                (assoc acc dep-entity (ig/ref [:fx/entity dep-entity])))
              {:spec spec}
              deps))))


(defmethod ig/init-key :fx/entity [entity-key {:keys [spec]}]
  (let [entity-type (uc/entity-key->entity-type entity-key)]
    (create-entity entity-type spec)))
