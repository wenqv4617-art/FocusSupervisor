# ============================================================================
#  :app 混淆规则（release 构建启用）
#
#  当前 release 未开启 minify，这里先留白并写好必须的 keep 骨架，
#  等真正打开 isMinifyEnabled 时按下面的注释逐条补。
# ============================================================================

# 无障碍服务由系统通过 AndroidManifest 反射实例化，必须保留。
-keep class com.focussupervisor.app.service.** { *; }

# Compose 运行时的默认规则由 AGP 自带，无需手写。
# 后续接入 Room / Retrofit / kotlinx.serialization 时在此追加对应规则。
