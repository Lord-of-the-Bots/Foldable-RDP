include_guard(GLOBAL)
get_filename_component(SOURCE_ROOT "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${SOURCE_ROOT}=.")
