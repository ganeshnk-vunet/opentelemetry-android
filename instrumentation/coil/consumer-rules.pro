# No special ProGuard rules required for the Coil instrumentation.
#
# CoilInstrumentation is public and retained as part of the ServiceLoader SPI manifest.
# VunetCoilEventListenerFactory and VunetCoilInterceptor are referenced directly by consumer
# code — R8 retains them through the normal reachability graph. No reflection is used.
