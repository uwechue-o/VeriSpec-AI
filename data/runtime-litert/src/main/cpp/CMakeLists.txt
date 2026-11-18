cmake_minimum_required(VERSION 3.22.1)
project(verispec_nnapi LANGUAGES C)

# Lists the NNAPI devices (ANeuralNetworks_getDevice*, API 29+) so a run can record
# what the platform offered and a manifest can name one of them. LiteRT's Java API
# has no way to ask.
add_library(verispec_nnapi SHARED nnapi_devices.c)
target_link_libraries(verispec_nnapi PRIVATE neuralnetworks)
target_compile_options(verispec_nnapi PRIVATE -fvisibility=hidden -O2 -Wall -Werror)
