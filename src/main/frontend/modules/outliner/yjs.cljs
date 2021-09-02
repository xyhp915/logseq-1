(ns frontend.modules.outliner.yjs
  (:require ["yjs" :as y]
            ["y-websocket" :as y-ws]
            [frontend.modules.outliner.tree :as tree]
            [frontend.modules.outliner.core :as outliner-core]
            [frontend.format.block :as block]
            [frontend.format.mldoc :as mldoc]
            [frontend.handler.editor :as editor]
            [frontend.state :as state]
            [frontend.db :as db]
            [frontend.db.model :as db-model]))


(def doc-local (y/Doc.))
(def doc-remote (y/Doc.))

;; (def wsProvider1 (y-ws/WebsocketProvider. "ws://192.168.1.149:1234", "test-user", doc-remote))

(defn- contentmap [] (.getMap doc-local "content"))
(defn- structarray [page-name] (.getArray doc-local (str page-name "-struct")))


(defn- assoc-contents [contents contentmap]
  (mapv (fn [[k v]] (.set contentmap k v)) contents))

(defn- distinct-struct [struct id-set]
  (loop [i 0]
    (when (< i (.-length struct))
      (let [s (.get struct i)]
        (if (instance? y/Array s)
          (do
            (distinct-struct s id-set)
            (recur (inc i)))
          (if (contains? @id-set s)
            (do
              (if (and
                   (>= (dec i) 0)
                   (< (inc i) (.-length struct))
                   (instance? y/Array (.get struct (dec i)))
                   (instance? y/Array (.get struct (inc i))))
                (let [next-item (.get struct (inc i))]
                  (distinct-struct next-item id-set)
                  (.push (.get struct (dec i)) (.toArray next-item))
                  (.delete struct (inc i))
                  (.delete struct i)
                  (recur i))
                (do
                  (.delete struct i)
                  (recur i))))
            (do
              (swap! id-set #(conj % s))
              (recur (inc i)))))))))

(defn- ->content-map [blocks map]
  (clojure.walk/postwalk (fn [v]
                           (when (and (map? v) (:block/uuid v))
                             (.set map (str (:block/uuid v)) (y/Text. (:block/content v))))
                           v)
                         blocks))

(defn- ->struct-array [blocks arr]
  (let [arr (or arr (y/Array.))]
    (mapv (fn [block-or-children]
            (when (map? block-or-children)
              (.push arr (clj->js [(or (str (:block/uuid block-or-children)) "")]))
              (when-let [children (:block/children block-or-children)]
                (let [child (->struct-array children nil)]
                  (when (and child (> (.-length child) 0))
                    (.push arr (clj->js [child])))))))
          blocks)
    arr))

(defn page-blocks->doc [page-blocks page-name]
  (let [t (tree/blocks->vec-tree page-blocks page-name)
        content (contentmap)
        struct (structarray page-name)]
    (->content-map t content)
    (->struct-array t struct)))

(defn- update-block-content [uuid]
  (let [content-map (contentmap)
        new-content (.get content-map uuid)
        block (db-model/query-block-by-uuid uuid)
        updated-block (editor/wrap-parse-block (update block :block/content (fn [_] new-content)))]
    (outliner-core/save-node (outliner-core/block updated-block))))

(defn- get-item-left&parent [item id]
  (let [item-content id
        item-array (.toArray (.-parent item))
        item-index (.indexOf item-array item-content)
        left-content (loop [i (dec item-index)]
                       (when (>= i 0)
                         (when-let [content (nth item-array i)]
                           (if (instance? y/Array content)
                             (recur (dec i))
                             content))))
        parent-array (.toArray (.-parent (.-parent item)))
        array-index (loop [i 0]
                      (when (< i (count parent-array))
                        (when-let [item (nth parent-array i)]
                          (if (instance? y/Array item)
                            (if (not= -1 (.indexOf (.toArray item) item-content))
                              i
                              (recur (inc i)))
                            (recur (inc i))
                            ))))
        parent-content (loop [i (dec array-index)]
                         (when (>= i 0)
                           (when-let [content (nth parent-array i)]
                             (if (instance? y/Array content)
                               (recur (dec i))
                               content))))]
    [left-content parent-content]))

(defn- insert-node [left-id parent-id id contentmap]
  {:pre [(seq parent-id)]}
  (let [left-id (or left-id parent-id)
        content (str "- " (.get contentmap id))
        target-node (outliner-core/block (db-model/query-block-by-uuid left-id))
        format (:block/format target-node)
        new-node (outliner-core/block
                  (or (db-model/query-block-by-uuid id)
                      (first (block/extract-blocks (mldoc/->edn content (mldoc/default-config format))
                                                   content true format))))

        sibling? (not= parent-id left-id)]
    (outliner-core/insert-node new-node target-node sibling?)))

(defn- delete-node [id]
  (when-let [block (db-model/query-block-by-uuid id)]
    (outliner-core/delete-node (outliner-core/block block) false)))

(defn- observe-struct-fn [events]
  (mapv (fn [event]
          (let [added-items (into [] (.-added (.-changes event)))
                ;; deleted-items (into [] (.-deleted (.-changes event)))
                ]
            (mapv (fn [item]
                    (mapv (fn [id]
                            (let [[left-content parent-content] (get-item-left&parent item id)]
                              (insert-node left-content parent-content id (contentmap))))
                          (.-arr (.-content item)))) added-items)
            ;; (mapv (fn [item]
            ;;         (mapv #(delete-node %) (.-arr (.-content item)))) deleted-items)
            ))
        events))

(defn- observe-content-fn [event]
  (let [keys (js->clj (into [] (.-keys event)))]
    (mapv (fn [[k v]]
            (case (get "action" v)
              "update" (update-block-content k)
              "delete" (delete-node k))) keys)))

(defn observe-page-doc [page-name doc]
  (let [struct (.getArray doc (str page-name "-struct"))
        content(.getMap doc (str page-name "-content"))]
    (.unobserveDeep struct observe-struct-fn)
    (.unobserve struct observe-content-fn)
    (.observeDeep struct observe-struct-fn)
    (.observe struct observe-content-fn)))

(defn merge-doc [doc1 doc2]
  (let [s1 (y/encodeStateVector doc1)
        s2 (y/encodeStateVector doc2)
        d1 (y/encodeStateAsUpdate doc1 s2)
        d2 (y/encodeStateAsUpdate doc2 s1)]
    (y/applyUpdate doc1 d2)
    (y/applyUpdate doc2 d1)))

(defn sync-doc [local remote]
  (.on remote "update" (fn [update]
                         (y/applyUpdate local update)))
  ;; (.on local "update" (fn [update]
  ;;                       (y/applyUpdate remote update)))
  )


(defn start-sync-page [page-name]
  (let [page-blocks (db/get-page-blocks page-name)]
    (page-blocks->doc page-blocks page-name)
    (observe-page-doc page-name doc-local)
    (sync-doc doc-local doc-remote)
    (merge-doc doc-remote doc-local)))

(defn stop-sync-page [page-name]
  ;; TODO
  )


(defn find-pos [struct id]
  (let [toplevel (js->clj (.toArray struct))
        index (.indexOf toplevel id)]
    (if (not= -1 index)
      [index]
      (loop [i 0]
        (if (>= i (count toplevel))
          nil
          (let [sublevel (get toplevel i)]
            (if (instance? y/Array sublevel)
              (if-let [index (find-pos sublevel id)]
                (vec (flatten [i index]))
                (recur (+ i 1)))
              (recur (+ i 1)))))))))

(defn inc-pos [pos]
  (conj (vec (butlast pos)) (inc (last pos))))

(defn inc-level-pos [pos]
  (conj (inc-pos pos) 0))

;;; outliner op + yjs op

;; (defn move-subtree [root target-node sibling?]
;;   (outliner-core/move-subtree root target-node sibling?))


;; (defn indent-outdent-nodes [nodes indent?]
;;   )


;; (defn delete-nodes [start-node end-node block-ids]

;;   )

(defn- nodes-tree->struct&content [nodes-tree]
  (let [contents (atom {})
        struct (clojure.walk/postwalk
                (fn [node]
                  (if (instance? outliner-core/Block node)
                    (let [block (:data node)
                          block-uuid (:block/uuid block)
                          block-content (:block/content block)]
                      (when block-uuid
                        (swap! contents (fn [o] (assoc o (str block-uuid) block-content))))
                      (str block-uuid))
                    node)) nodes-tree)]
    [struct @contents]))

(defn- goto-innermost-struct-array [pos struct]
  (loop [i 0 s struct]
    (if (> i (- (count pos) 2))
      s
      (recur (inc i) (.get s (get pos i))))))

(defn- insert-nodes-aux [insert-structs pos struct]
  "insert INSERT-STRUCTS at POS"
  (loop [i 0 pos pos]
    (when (< i (count insert-structs))
      (let [s (nth insert-structs i)
            struct* (goto-innermost-struct-array pos struct)]
        (cond
          (vector? s)
          (let [pos* (conj pos 0)]
            (.insert struct* (last pos) (clj->js [(y/Array.)]))
            (insert-nodes-aux s pos* struct)
            (recur (inc i) (inc-pos pos)))

          :else
          (do
            (.insert struct* (last pos) (clj->js [s]))
            (recur (inc i) (inc-pos pos))))))))


(defn- next-sibling-pos [pos struct]
  (let [inner-struct (goto-innermost-struct-array pos struct)
        next-item (.get inner-struct (inc (last pos)))]
    (if (instance? y/Array next-item)
      (conj (vec (butlast pos)) (+ 2 (last pos)))
      (conj (vec (butlast pos)) (+ 1 (last pos))))))

(defn- next-non-sibling-pos! [pos struct]
  "create a y/Array when no child follows item at POS"
  (let [inner-struct (goto-innermost-struct-array pos struct)
        next-item (.get inner-struct (inc (last pos)))]
    (when-not (instance? y/Array next-item)
      (.insert inner-struct (inc (last pos)) (clj->js [(y/Array.)])))
    (inc-level-pos pos)))


(defn insert-nodes [page-name new-nodes-tree target-uuid sibling?]
  (let [[structs contents] (nodes-tree->struct&content new-nodes-tree)
        struct (structarray page-name)]
    (when-let [target-pos (find-pos (structarray page-name) (str target-uuid))]
      (let [pos (if sibling?
                  (next-sibling-pos target-pos struct)
                  (next-non-sibling-pos! target-pos struct))]
        (insert-nodes-aux structs pos (structarray page-name))
        (assoc-contents contents (contentmap))))))

(comment
  (def page-blocks (db-model/get-page-blocks "page-name"))
  (page-blocks->doc page-blocks "page-name")

  (def test-struct (.getArray doc-local "page-name-struct"))
  (.delete test-struct 0 (.-length test-struct))
  (.insert test-struct 0 (clj->js ["1"]))
  (.insert test-struct 1 (clj->js ["2"]))
  (.insert test-struct 2 (clj->js [(y/Array.)]))
  (def pos2 (find-pos test-struct "2"))
  (assert (= [1] pos2))
  (.insert (.get test-struct 2) 0 (clj->js ["3"]))
  (assert (= [2 1] (next-sibling-pos [2 0] test-struct)))
  (assert (= [2 1 0] (next-non-sibling-pos! [2 0] test-struct)))

  ;; insert-nodes
  (insert-nodes "page-name" (first xxxx) "61309f20-836c-4e47-894e-cc126050f039" true)
  )
