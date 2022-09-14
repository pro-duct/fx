(ns fx.repo)


(defprotocol IRepo
  (save! [entity data])
  (update! [entity data params])
  (delete! [entity params])
  (find! [entity params])
  (find-all! [entity] [entity params]))
