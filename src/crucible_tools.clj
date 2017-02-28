(ns crucible-tools
  (:require [cheshire.core :as json]
            [clojure.test :refer :all :as t]
            [clojure.java.io :as io]
            [crucible.resources :refer [spec-or-ref defresource] :as res]
            [camel-snake-kebab.core :refer [->kebab-case]]
            [clojure.spec :as s]
            [clojure.string :as st]))

(defn ->spec-name [resource-type resource-name]
  (let [[ns-part el-part] (st/split resource-type #"\.")
        ns (str "crucible.generated." (st/replace ns-part #"::" "."))
        el (str el-part (when (and (not-empty el-part) (not-empty resource-name)) "-") resource-name)
        x (keyword ns el)] ;->kebab-case
    x))

(defn primitive-type->spec [p]
  (case p
    "String"  `(spec-or-ref string?)
    "Number"  `(spec-or-ref number?)
    "Integer" `(spec-or-ref integer?)
    "Boolean" `(spec-or-ref boolean?)
    `(spec-or-ref string?)))

(defn non-primitive-type->spec [r i]
  (let [x (->spec-name r i)]
    `(spec-or-ref ~x)))

(defn complex-type->spec [res type coll-item-type]
  (case type
    "List" `(s/coll-of ~coll-item-type :kind vector?)
    "Map"  `(s/map-of string? ~coll-item-type)
    nil))

(defn extract-spec-type [res prim-type type item-type coll-item-type]
  (if prim-type
    (primitive-type->spec prim-type)
    (complex-type->spec res type coll-item-type)))

(defn ->spec [res x {:strs [Documentation PrimitiveType Required UpdateType DuplicatesAllowed PrimitiveItemType Type ItemType] :as d}]
  (let [spec-name      (->spec-name res x)
        coll-item-type (if PrimitiveItemType
                         (primitive-type->spec PrimitiveItemType)
                         (non-primitive-type->spec res ItemType))
        spec-type      (when coll-item-type
                         (extract-spec-type res PrimitiveType Type ItemType coll-item-type))]
    (when spec-type
      `[(s/def ~spec-name ~spec-type)])))

(defn get-type-properties [r props]
  (mapcat #(->spec r %1 (get props %1)) (keys props)))

(defn ->spec-keys [n prefix ks]
  (->> ks
       (keep first)
       (map #(keyword n (str prefix (when (not-empty prefix) "-") %)))
       (into [])))

(defn extract-properties [p properties]
  (let [{required true optional false} (group-by #(get (val %) "Required") properties)
        n (->spec-name p nil)]
    `(s/def ~n (s/keys :req ~(->spec-keys (namespace n) (name n) required) :opt ~(->spec-keys (namespace n) (name n) optional)))))

(defn extract-resources [[p v]]
  (let [properties (get v "Properties")]
    (conj (get-type-properties p properties)
          (extract-properties p properties))))

(defn parse-resources []
  (let [aws-spec (json/decode (slurp #_ "https://d3teyb21fexa9r.cloudfront.net/latest/CloudFormationResourceSpecification.json"
                                     "test-resources/CloudFormationResourceSpecification.json"))
        property-types (get aws-spec "PropertyTypes")
        resource-types (get aws-spec "ResourceTypes")])
  (->> (concat property-types resource-types)
       (mapcat extract-resources)
       (remove nil?)
       (doall)))

