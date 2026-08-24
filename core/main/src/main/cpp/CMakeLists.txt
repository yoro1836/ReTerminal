cmake_minimum_required(VERSION 3.22.1)
project(reterminal-native C)

add_executable(avf-terminal-bridge avf-terminal-bridge.c)
set_target_properties(avf-terminal-bridge PROPERTIES
    OUTPUT_NAME "avf-terminal-bridge"
    PREFIX "lib"
    SUFFIX ".so"
)
