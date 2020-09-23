(ns dv.devcards-fulcro3
  (:require-macros [dv.devcards-fulcro3 :refer [make-card]])
  (:require
    [borkdude.dynaload-cljs :refer-macros [dynaload]]
    [cljs.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.merge :as f.merge]
    [com.fulcrologic.fulcro.algorithms.normalize :refer [tree->db]]
    [com.fulcrologic.fulcro.application :as fa]
    [com.fulcrologic.fulcro.components :as fc]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.guardrails.core :refer [>defn | ? =>]]
    [com.fulcrologic.fulcro.inspect.inspect-client :as fi.client]
    [devcards.core]
    [nubank.workspaces.card-types.util :as ct.util]
    [nubank.workspaces.data :as data]
    [nubank.workspaces.model :as wsm]
    [nubank.workspaces.ui :as ui]
    [nubank.workspaces.ui.core :as uc]))

(def html (dynaload 'sablono.core/html))
(def r-as-element (dynaload 'reagent.core/as-element))

;; This was copied from the workspaces fulcro 3 code
;; https://github.com/awkay/workspaces/blob/e1d3c21042229309c5df954f8156b5918b0c9a40/src/nubank/workspaces/card_types/fulcro3.cljs

(s/def ::root any?)
(s/def ::wrap-root? boolean?)
(s/def ::app map?)
(s/def ::persistence-key any?)
(s/def ::initial-state (s/or :fn? fn? :factory-param any?))
(s/def ::root-state map?)
(s/def ::computed map?)
(s/def ::root-node-props map?)

(defonce persistent-apps* (atom {}))

(defn safe-initial-state [comp params]
  (if (fc/has-initial-app-state? comp)
    (fc/get-initial-state comp params)
    params))

(defn make-root [Root]
  (let [generated-name (gensym)
        component-key  (keyword "dv.devcards-fulcro3" (name generated-name))]
    (fc/configure-component! (fn *dyn-root* [])
      component-key
      {:initial-state (fn [_ params]
                        {:ui/root (or (safe-initial-state Root params) {})})
       :query         (fn [_] [:fulcro.inspect.core/app-id {:ui/root (fc/get-query Root)}])
       :render        (fn [this]
                        (let [{:ui/keys [root]} (fc/props this)
                              Root     (-> Root fc/class->registry-key fc/registry-key->class)
                              factory  (fc/factory Root)
                              computed (fc/shared this ::computed)]
                          (if (seq root)
                            (factory (cond-> root computed (fc/computed computed))))))})))

(defn fulcro-initial-state [{::keys [initial-state wrap-root? root root-state]
                             :or    {wrap-root? true initial-state {}}}]
  (let [state-tree (if (fn? initial-state)
                     (initial-state (safe-initial-state root nil))
                     (safe-initial-state root initial-state))
        wrapped    (merge
                     (if wrap-root? {:ui/root state-tree} state-tree)
                     root-state)
        Root       (if wrap-root? (make-root root) root)
        db         (tree->db Root wrapped true (f.merge/pre-merge-transform {}))]
    db))

(defn upsert-app
  [{::keys                    [app persistence-key computed]
    :fulcro.inspect.core/keys [app-id]
    :as                       config}]
  (if-let [instance (and persistence-key (get @persistent-apps* persistence-key))]
    instance
    (let [app-options (cond-> app
                        (not (contains? app :initial-state))
                        (assoc :initial-db (fulcro-initial-state config))

                        computed
                        (update :shared assoc ::computed computed)

                        app-id
                        (assoc-in [:initial-db :fulcro.inspect.core/app-id] app-id))
          ;; TASK: explicit initial state handling
          instance    (fa/fulcro-app app-options)]

      ;(println "APP options : " app-options)
      (if persistence-key (swap! persistent-apps* assoc persistence-key instance))
      instance)))

(defn dispose-app [{::keys [persistence-key] :as app}]
  (if persistence-key (swap! persistent-apps* dissoc persistence-key))
  (when-let [app-uuid (fi.client/app-uuid app)]
    (fi.client/dispose-app app-uuid)))

(>defn mount-at
  [app {::keys [root wrap-root? persistence-key] :or {wrap-root? true}} node]
  [::fa/app map? some? => any?]
  (let [instance (if wrap-root? (make-root root) root)]
    (fa/mount! app instance node {:initialize-state? false})
    (when persistence-key (swap! persistent-apps* assoc persistence-key app))
    app))

(defn inspector-set-app [card-id]
  (let [{::keys [app]} (data/active-card card-id)
        app-uuid (fi.client/app-uuid app)]
    (when app-uuid (fi.client/set-active-app app-uuid))))

(defn fulcro-card-init
  [{::wsm/keys [card-id]
    :as        card}
   config]
  (let [app (upsert-app (assoc config :fulcro.inspect.core/app-id card-id))]
    (ct.util/positioned-card card
      {::wsm/dispose
       (fn [node]
         (dispose-app app)
         (js/ReactDOM.unmountComponentAtNode node))

       ::wsm/refresh
       (fn [_] (fa/force-root-render! app))

       ::wsm/render
       (fn [node]
         (swap! data/active-cards* assoc-in [card-id ::app] app)
         (mount-at app config node))

       ::wsm/render-toolbar
       (fn []
         (dom/div
           (uc/button {:onClick #(inspector-set-app card-id)}
             "Inspector")
           (uc/button {:onClick #(ui/restart-card card-id)}
             "Restart")))

       ::app
       app})))

(comment
  ;; 2 steps

  (let [config {::root SomeComponent ::wrap-root? true}
        app    (upsert-app (assoc config :fulcro.inspect.core/app-id card-id))])
  (mount-at app config node)
  )
