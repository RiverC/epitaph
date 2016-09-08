(ns epitaph.app
  (:require [clojure.string :as str]
            [epitaph.civs :refer [civ-tick gen-civ possible-techs process-event]]
            [om.core :as om]
            [om-tools.core :refer-macros [defcomponent]]
            [om-tools.dom :as dom]))

(def synth
  (.toMaster (js/Tone.Synth.)))

(defn scale
  "Converts the number `x` from the scale `[old-min old-max]` to the scale
   `[new-min new-max]`."
  [x [old-min old-max] [new-min new-max]]
  (let [old-range (- old-max old-min)
        new-range (- new-max new-min)]
    (+ (/ (* (- x old-min) new-range) old-range) new-min)))

(defonce app-state
  (let [stardate (+ 2200 (rand-int 700))]
    (atom {:civs [(gen-civ stardate)]
           :stardate stardate
           :last-intervened 0
           :sound-on? true})))

(defn can-intervene? [state]
  (< (+ (:last-intervened state) 10) (:stardate state)))

(defn play-notification-sound! [pitch]
  (when (:sound-on? @app-state)
    (.triggerAttackRelease synth pitch "8n")))

(defn get-notification-pitch [old-civs new-civs]
  (cond
    (> (count new-civs) (count old-civs))
      (:notification-pitch (peek new-civs))
    (> (count (filter :extinct? new-civs))
       (count (filter :extinct? old-civs)))
      "C3"
    :else
      (let [civs-with-new-events
            (->> (range (count old-civs))
                 (filter #(> (count (:events (nth new-civs %)))
                             (count (:events (nth old-civs %)))))
                 (map new-civs))]
        (when (seq civs-with-new-events)
          (rand-nth (mapv :notification-pitch civs-with-new-events))))))

(defn tick []
  (om/transact! (om/root-cursor app-state)
    (fn [state]
      (let [new-stardate (inc (:stardate state))
            civs (mapv #(civ-tick % new-stardate) (:civs state))
            ;; new civs more likely to spawn if all existing civs are extinct
            new-civ-chance (if (every? :extinct? civs) (/ 1 50) (/ 1 250))
            civs (cond-> civs (< (rand) new-civ-chance)
                              (conj (gen-civ new-stardate)))]
        (when-let [pitch (get-notification-pitch (:civs state) civs)]
          (play-notification-sound! pitch))
        (assoc state :civs civs :stardate new-stardate)))))

(defcomponent event-view [data owner]
  (render [_]
    (dom/p {:class "event"} (:desc data))))

(defcomponent civ-view [data owner]
  (render [_]
    (dom/div {:class (cond-> "civ" (:extinct? data) (str " extinct"))
              :style {:opacity (when (:extinct? data)
                                 (-> (:cycles-since-extinction data)
                                     (scale [0 (+ 160 (* 20 (count (:events data))))]
                                            [0.75 0.1])
                                     (max 0.1)
                                     (str)))}}
      (dom/div {:class "profile"}
        (dom/h3 {:class "name"} (:name data)))
      ;; events
      (dom/div {:class "events"}
        (om/build-all event-view (:events data)))
      ;; enlighten
      (when (and (not (:extinct? data)) (seq (possible-techs data)))
        (dom/div {:class "enlighten"}
          (dom/p {}
            "We could teach them the secrets of "
            (for [part (interpose ", or of " (possible-techs data))]
              (if (map? part)
                (let [tech part]
                  (dom/a {:href "#"
                          :on-click (fn [e]
                                      (.preventDefault e)
                                      (play-notification-sound! (:notification-pitch data))
                                      (om/transact! data []
                                        #(process-event % tech (:stardate @app-state)))
                                      (om/transact! (om/root-cursor app-state) []
                                        #(assoc % :last-intervened (:stardate %))))}
                    (str/replace (name (:name tech)) #"-" " ")))
                (dom/span part)))))))))

(defcomponent app [data owner]
  (render [_]
    (dom/div {:class (cond-> "app" (not (can-intervene? data))
                                   (str " cannot-intervene"))}
      (dom/p {:class "stardate"}
        (dom/span (str "Stardate " (:stardate data)))
        (when (pos? (:last-intervened data))
          (dom/span {}
            " | Last intervened in "
            (dom/span {:class "last-intervened"} (:last-intervened data)))))
      (dom/div {:class "right"}
        (dom/a {:class (if (:sound-on? data) "icon-sound-on" "icon-sound-off")
                :on-click #(om/transact! data :sound-on? not)}))
      (dom/div {:class "civs"}
        (om/build-all civ-view (reverse (:civs data)) {:key :name})))))

(defn init []
  (enable-console-print!)
  (om/root app app-state {:target (js/document.getElementById "app")})
  (play-notification-sound! (:notification-pitch (first (:civs @app-state))))
  (js/setInterval tick 500))

(init)