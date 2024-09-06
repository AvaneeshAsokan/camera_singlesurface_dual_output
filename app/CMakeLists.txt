cmake_minimum_required(VERSION 3.22.1)

# Specify the C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library( # Sets the name of the library.
             yuv_copy
             # Sets the library as a shared library.
             SHARED
             # Provides a relative path to your source file(s).
             src/main/cpp/yuv_copy.cpp
             )

# Include NEON support
set_target_properties(yuv_copy PROPERTIES
        COMPILE_FLAGS "-mfpu=neon"
)


find_library( # Sets the name of the path variable.
              log-lib
              # Specifies the name of the NDK library that
              # you want CMake to locate.
              log )

target_link_libraries( # Specifies the target library.
                       yuv_copy
                       # Links the target library to the log library
                       # included in the NDK.
                       ${log-lib} )