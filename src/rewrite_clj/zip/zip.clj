;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

;functional hierarchical zipper, with navigation, editing and enumeration
;see Huet

(ns ^{:doc "Functional hierarchical zipper, with navigation, editing,
  and enumeration.  See Huet"
       :author "Rich Hickey"}
  rewrite-clj.zip.zip
  (:refer-clojure :exclude (replace remove next))
  (:require [rewrite-clj.node.protocols :as node]))

(defn zipper
  "Creates a new zipper structure."
  [root]
  [root nil [1 1]])

(defn node
  "Returns the node at loc"
  [loc] (loc 0))

(defn branch?
  "Returns true if the node at loc is a branch"
  [loc]
    (node/inner? (node loc)))

(defn children
  "Returns a seq of the children of node at loc, which must be a branch"
  [loc]
    (if (branch? loc)
      (seq (node/children (node loc)))
      (throw (Exception. "called children on a leaf node"))))

(defn make-node
  "Returns a new branch node, given an existing node and new
  children. The loc is only used to supply the constructor."
  [loc node children]
    (node/replace-children node children))

(defn path
  "Returns a seq of nodes leading to this loc"
  [loc]
    (:pnodes (loc 1)))

(defn position
  "Returns [row col] of the start of the current node"
  [loc]
  (loc 2))

(defn lefts
  "Returns a seq of the left siblings of this loc"
  [loc]
    (seq (:l (loc 1))))

(defn down
  "Returns the loc of the leftmost child of the node at this loc, or
  nil if no children"
  [loc]
    (when (branch? loc)
      (let [[node path [row col]] loc
            [c & cnext :as cs] (children loc)]
        (when cs
          [c
           {:l [] 
            :pnodes (if path (conj (:pnodes path) node) [node]) 
            :ppath path 
            :r cnext}
           [row (+ col (node/leader-length node))]]))))

(defn up
  "Returns the loc of the parent of the node at this loc, or nil if at
  the top"
  [loc]
    (let [[node {l :l, ppath :ppath, pnodes :pnodes r :r, changed? :changed?, :as path}] loc]
      (when pnodes
        (let [pnode (peek pnodes)]
          (if changed?
            [(make-node loc pnode (concat l (cons node r))) 
             (and ppath (assoc ppath :changed? true))
             (loc 2)]
            [pnode ppath (loc 2)])))))

(defn root
  "zips all the way up and returns the root node, reflecting any
 changes."
  [loc]
    (if (= :end (loc 1))
      (node loc)
      (let [p (up loc)]
        (if p
          (recur p)
          (node loc)))))

(defn right
  "Returns the loc of the right sibling of the node at this loc, or nil"
  [loc]
    (let [[node {l :l  [r & rnext :as rs] :r :as path} pos] loc]
      (when (and path rs)
        [r
         (assoc path :l (conj l node) :r rnext)
         (node/+extent pos (node/extent node))])))

(defn rightmost
  "Returns the loc of the rightmost sibling of the node at this loc, or self"
  [loc]
  (if-let [next (right loc)]
    (recur next)
    loc))

(defn left
  "Returns the loc of the left sibling of the node at this loc, or nil"
  [loc]
    (let [[node {l :l r :r :as path}] loc]
      (when (and path (seq l))
        [(peek l) (assoc path :l (pop l) :r (cons node r)) (loc 2)])))

(defn leftmost
  "Returns the loc of the leftmost sibling of the node at this loc, or self"
  [loc]
    (let [[node {l :l r :r :as path}] loc]
      (if (and path (seq l))
        [(first l) (assoc path :l [] :r (concat (rest l) [node] r)) (loc 2)]
        loc)))

(defn insert-left
  "Inserts the item as the left sibling of the node at this loc,
 without moving"
  [loc item]
    (let [[node {l :l :as path}] loc]
      (if (nil? path)
        (throw (new Exception "Insert at top"))
        [node (assoc path :l (conj l item) :changed? true) (loc 2)])))

(defn insert-right
  "Inserts the item as the right sibling of the node at this loc,
  without moving"
  [loc item]
    (let [[node {r :r :as path}] loc]
      (if (nil? path)
        (throw (new Exception "Insert at top"))
        [node (assoc path :r (cons item r) :changed? true) (loc 2)])))

(defn replace
  "Replaces the node at this loc, without moving"
  [loc node]
    (let [[_ path] loc]
      [node (assoc path :changed? true) (loc 2)]))

(defn edit
  "Replaces the node at this loc with the value of (f node args)"
  [loc f & args]
    (replace loc (apply f (node loc) args)))

(defn insert-child
  "Inserts the item as the leftmost child of the node at this loc,
  without moving"
  [loc item]
    (replace loc (make-node loc (node loc) (cons item (children loc)))))

(defn append-child
  "Inserts the item as the rightmost child of the node at this loc,
  without moving"
  [loc item]
    (replace loc (make-node loc (node loc) (concat (children loc) [item]))))

(defn next
  "Moves to the next loc in the hierarchy, depth-first. When reaching
  the end, returns a distinguished loc detectable via end?. If already
  at the end, stays there."
  [loc]
    (if (= :end (loc 1))
      loc
      (or 
       (and (branch? loc) (down loc))
       (right loc)
       (loop [p loc]
         (if (up p)
           (or (right (up p)) (recur (up p)))
           [(node p) :end (loc 2)])))))

(defn prev
  "Moves to the previous loc in the hierarchy, depth-first. If already
  at the root, returns nil."
  [loc]
    (if-let [lloc (left loc)]
      (loop [loc lloc]
        (if-let [child (and (branch? loc) (down loc))]
          (recur (rightmost child))
          loc))
      (up loc)))

(defn end?
  "Returns true if loc represents the end of a depth-first walk"
  [loc]
    (= :end (loc 1)))

(defn remove
  "Removes the node at loc, returning the loc that would have preceded
  it in a depth-first walk."
  [loc]
    (let [[node {l :l, ppath :ppath, pnodes :pnodes, rs :r, :as path}] loc]
      (if (nil? path)
        (throw (new Exception "Remove at top"))
        (if (pos? (count l))
          (loop [loc [(peek l) (assoc path :l (pop l) :changed? true) (loc 2)]]
            (if-let [child (and (branch? loc) (down loc))]
              (recur (rightmost child))
              loc))
          [(make-node loc (peek pnodes) rs) 
                     (and ppath (assoc ppath :changed? true))
                     (loc 2)]))))
