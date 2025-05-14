cmake_minimum_required(VERSION 3.26)
project(util)

set(ENV{JAVA_HOME} "/home/x150/.jdks/openjdk-22.0.1")

find_package (Java REQUIRED)
find_package (JNI REQUIRED)
include (UseJava)

set(CMAKE_CXX_STANDARD 23)

add_executable(util
        util.h
        util.cpp
        export.h
        antiHook.cpp
        antiHook.h
        chacha20.h
        chacha20.cpp
        test_main.cpp
)

#target_include_directories (util PRIVATE "/home/x150/.jdks/openjdk-20.0.2/include" "/home/x150/.jdks/openjdk-20.0.2/include/linux")
target_include_directories (util PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries (util PRIVATE ${JNI_LIBRARIES})