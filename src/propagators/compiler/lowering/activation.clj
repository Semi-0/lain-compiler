(ns propagators.compiler.lowering.activation
  "Run declared activation propagators and the readers of boundary inputs."
  (:require [propagators.infra.cells.snapshot :refer [pop-inputs]]
            [propagators.infra.core :as core]
            [propagators.infra.helpers.task-queue :as tq]
            [propagators.infra.network :as net]))

(defn run-network
  [n inner-inputs prop-ids]
  (let [input-prop-ids (pop-inputs inner-inputs (net/net-graph n))
        tasks (tq/enqueue-all tq/empty-queue
                              (concat prop-ids input-prop-ids))]
    (core/run-tasks tasks n)))
