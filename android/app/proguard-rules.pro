# Release 使用 R8（minify + shrink）。不要关闭优化，也不要为 vela.app.hooks 添加 -keep。
# hooks 源码集只编进 debug 和 perf，release 的 mapping 里不得出现 vela.app.hooks。

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
