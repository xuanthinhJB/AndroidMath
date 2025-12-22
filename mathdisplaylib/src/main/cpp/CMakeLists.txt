cmake_minimum_required(VERSION 3.4.1)

# Build Freetype as a subdirectory (disable optional dependencies)
set(FT_DISABLE_ZLIB ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "" FORCE)
set(FT_DISABLE_PNG ON CACHE BOOL "" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BROTLI ON CACHE BOOL "" FORCE)
add_subdirectory(freetype)

add_library(main SHARED freetype_jni.cpp)

target_include_directories(main PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/freetype/include)
target_link_libraries(main freetype log android)
