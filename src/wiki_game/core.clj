(ns wiki-game.core
  (:require [org.httpkit.client :as http])
  (:use net.cgrand.enlive-html))

(defn parse-html
  [html]
  (html-resource (java.io.StringReader. html)))

(defn normalize-url
  [url]
  (let [hash-index (.indexOf url "#")]
    (if (>= hash-index 0)
      (.substring url 0 hash-index)
      url
      )
    )
  )

(defn extract-links
  [parsed-html]
  (map #(get (:attrs %) :href) (select parsed-html [(attr-starts :href "/wiki")])))


(defn get-body
  [url]
  (:body @(http/get (str "http://thewikigame.com" url) {:headers {"Cookie" "sessionid=jkpxxzzvbovx9xgcma2il0q1yueswc8n; "}})))

(defn create-vertex
  [origin dest]
  {dest origin})

(defn links
  [url]
  (->> url
    (get-body)
    (parse-html)
    (extract-links)
    (set))
  )

(defn build-graph
  [start end]
  (loop [links-graph {}
         to-do [start]]
    (if (or (contains? links-graph end) (= 0 (count to-do)))
      links-graph
      (let [current-path (first to-do)
            page-links (filter #(not (or (contains? links-graph %) (contains? to-do %))) (links current-path))]
        (recur (reduce #(assoc %1 %2 current-path) links-graph page-links)
               (into [] (concat (next to-do) page-links)))
        )
      )
    )
  )


(defn wikipedia-min-path
  [start end]
  (loop [current-step end
         path-head start
         path []
         graph (build-graph start end)]
    (if (= current-step path-head)
      (cons current-step path)
      (recur (get graph current-step)
             path-head
             (cons current-step path)
             graph)
      )
    )
  )