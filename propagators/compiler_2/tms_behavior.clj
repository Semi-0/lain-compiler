(ns propagators.compiler-2.tms-behavior
  "Compatibility facade for compiler-2 TMS/behavior operators.

  New code should require propagators.compiler-2.tms,
  propagators.compiler-2.behavior, or propagators.compiler-2.legacy directly."
  (:require [propagators.compiler-2.behavior :as behavior]
            [propagators.compiler-2.legacy :as legacy]
            [propagators.compiler-2.tms :as tms]))

(def distributed-behavior-operator behavior/distributed-behavior-operator)
(def stable-distributed-behavior-operator behavior/stable-distributed-behavior-operator)
(def behavior-point-operator behavior/behavior-point-operator)
(def behavior-event-operator behavior/behavior-event-operator)
(def behavior-empty-state-operator behavior/behavior-empty-state-operator)
(def behavior-add-event-operator behavior/behavior-add-event-operator)
(def behavior-state-events-operator behavior/behavior-state-events-operator)
(def behavior-update-field-operator behavior/behavior-update-field-operator)
(def behavior-assoc-event-operator behavior/behavior-assoc-event-operator)
(def behavior-state-from-events-operator behavior/behavior-state-from-events-operator)
(def behavior-retain-last-operator behavior/behavior-retain-last-operator)
(def behavior-operator behavior/behavior-operator)
(def bind-behavior-operators behavior/bind-behavior-operators)
(def behavior-tms-env behavior/behavior-tms-env)

(def tms-closure-operator tms/tms-closure-operator)
(def distributed-premise-closure-operator tms/distributed-premise-closure-operator)
(def premise-input-operator tms/premise-input-operator)
(def premise-content-input-operator tms/premise-content-input-operator)
(def premise-state-operator tms/premise-state-operator)
(def bind-distributed-tms-operators tms/bind-distributed-tms-operators)

(def legacy-premise-closure-operator legacy/legacy-premise-closure-operator)
(def bind-legacy-central-tms-operators legacy/bind-legacy-central-tms-operators)
(def legacy-central-tms-env legacy/legacy-central-tms-env)
