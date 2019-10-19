# Roadmap

I'd love to put some work into the APIs and to fix issues / bugs users might run into. However, this would need actual users. Afterwards I'd love to release version 1.0.0.

From there on Or even before releasing 1.0.0 I'd love to put work into our front-end in order to be able to visualize differences between revisions, to execute Queries, to be able to update databases and resources and to open specific revisions.

## Near future

- **Sharding** I'll have a look into how best to write and read from a distributed transaction log based on Apache BookKeeper most probably. Main goal is to shard SirixDB databases, that is replicate resources, partition a database... *However a community discussion would be best*.

- **Rewrite rules for the query compiler** We have to figure out how to rewrite the AST in Brackit to automatically take indexes and various statistics into account. In the future: Cost based optimizer. However I'm no query compiler expert, so as always a community effort would be awesome

- **Full text index** Would be awesome to provide full text indexing and querying capabilities

- **Support for other data (Graphs?!**

