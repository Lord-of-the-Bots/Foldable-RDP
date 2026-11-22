cmake_minimum_required(VERSION 3.22.1)
project(fold_display C)

set(FREERDP_LIBS "${CMAKE_CURRENT_LIST_DIR}/../../../jni/libs/deps/FreeRDP/client/Android/Studio/freeRDPCore/src/main/jniLibs/${ANDROID_ABI}")
add_library(fold_display SHARED display_control.c clipboard_channel.c)
target_include_directories(fold_display PRIVATE "${FREERDP_LIBS}/include/freerdp2" "${FREERDP_LIBS}/include/winpr2")
target_link_directories(fold_display PRIVATE "${FREERDP_LIBS}")
target_link_libraries(fold_display PRIVATE freerdp2 freerdp-client2 winpr2)
target_compile_options(fold_display PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_options(fold_display PRIVATE "-Wl,-z,max-page-size=16384")

get_filename_component(SOURCE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
target_compile_options(fold_display PRIVATE "-ffile-prefix-map=${SOURCE_ROOT}=.")
