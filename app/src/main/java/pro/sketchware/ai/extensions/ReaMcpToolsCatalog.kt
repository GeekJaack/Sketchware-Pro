package pro.sketchware.ai.extensions

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import pro.sketchware.ai.project.ApkProjectReconstructor
import pro.sketchware.ai.project.ProjectGateway
import pro.sketchware.ai.provider.ToolParameterProperty
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Complete catalog of REA (Reverse Engineer Anything) MCP tools for AI Agent Studio (with host-only
 * ADB tools stripped out for direct on-device Android execution).
 *
 * Covers 205 REA tools across Binary Analysis, Memory & Data, Functions & Calls,
 * Comments & Annotations, Bookmarks, Strings & Data Types, Cross References,
 * Tracing & Runtime Analysis, Decompilation, Binary Format Inspection, iOS/macOS,
 * .NET Inspection, Disassembly, Frida Instrumentation, Cutter, GDB, Rizin Debugging,
 * Packet Capture, Signal Processing, Android Tools, Apple Platforms, WebAssembly,
 * Android APK, EVM, SQLite, Crash Analysis, Firmware, Web Analysis, PCAP,
 * Flutter, APKTool, Web Export, Browser, Electron, Application Analysis,
 * Reconstruction, Unknowns Tracking, and Context.
 */
object ReaMcpToolsCatalog {

    const val TOTAL_TOOLS_COUNT: Int = 205

    val REA_MCP_TOOLS_MARKDOWN: String = "# REA (Reverse Engineer Anything) MCP Tools\n## Binary Analysis\n\n- `inspect_analysis_settings` - Inspect current analysis settings\n- `update_analysis_settings` - Update analysis settings\n- `current_address` - Get current address\n- `goto_address` - Navigate to address\n- `current_document` - Get current document\n- `list_documents` - List all documents\n- `current_procedure` - Get current procedure\n- `procedure_address` - Get procedure address\n- `procedure_info` - Get procedure information\n- `procedure_assembly` - Get procedure assembly\n- `procedure_pseudo_code` - Get procedure pseudo-code\n- `read_function_instructions` - Read function instructions\n- `next_address` - Get next address\n- `prev_address` - Get previous address\n\n## Memory and Data\n\n- `read_bytes` - Read bytes from memory\n- `search_bytes` - Search for byte patterns\n- `address_to_file_offset` - Convert address to file offset\n- `address_name` - Get address name\n- `list_names` - List all names in binary\n- `set_address_name` - Set name at address\n- `set_addresses_names` - Set multiple names\n\n## Functions and Calls\n\n- `list_procedures` - List all procedures\n- `search_procedures` - Search procedures\n- `procedure_callers` - Get function callers\n- `procedure_callees` - Get function callees\n- `procedure_references` - Get procedure references\n- `resolve_containing_procedure` - Resolve containing procedure\n- `resolve_native_call_targets` - Resolve call targets\n\n## Comments and Annotations\n\n- `comment` - Add comment\n- `inline_comment` - Add inline comment\n- `set_comment` - Set comment at address\n- `set_inline_comment` - Set inline comment\n- `annotate_native_function` - Annotate native function\n\n## Bookmarks\n\n- `list_bookmarks` - List all bookmarks\n- `set_bookmark` - Set bookmark\n- `unset_bookmark` - Remove bookmark\n\n## Strings and Data Types\n\n- `list_strings` - List all strings\n- `search_strings` - Search for strings\n- `list_segments` - List all segments\n- `inspect_native_data_type` - Inspect native data type\n- `inspect_native_instruction` - Inspect native instruction\n- `inspect_native_load_image` - Inspect native load image\n\n## Cross References\n\n- `xrefs` - Get cross references\n- `find_xrefs_to_name` - Find cross references to name\n\n## Tracing and Runtime Analysis\n\n- `trace_native_values` - Trace native values\n- `inspect_native_dispatch_metadata` - Inspect dispatch metadata\n- `trace_feature` - Trace feature execution\n- `trace_call_path` - Trace call path\n- `trace_native_ui_action` - Trace UI action\n- `observe_native_ui` - Observe native UI\n- `capture_native_ui_scenario` - Capture UI scenario\n- `observe_native_calls` - Observe native calls\n\n## Decompilation\n\n- `batch_decompile` - Batch decompile functions\n- `decompile_all` - Decompile all functions\n- `get_call_graph` - Get call graph\n- `analyze_function` - Analyze function\n\n## Binary Format Inspection\n\n- `inspect_macho` - Inspect Mach-O binaries\n- `inspect_signature` - Inspect binary signature\n- `inspect_plist` - Inspect plist files\n- `list_architectures` - List architectures in binary\n- `demangle_swift` - Demangle Swift symbols\n- `inspect_artifact` - Inspect artifact\n- `extract_artifact` - Extract artifact\n\n## iOS/macOS Tools\n\n- `get_objc_classes` - List Objective-C classes\n- `get_objc_protocols` - List Objective-C protocols\n- `decode_interface_builder` - Decode Interface Builder files\n- `inspect_keyed_archive` - inspect Keyed Archive\n- `inspect_asset_catalog` - Inspect asset catalog\n- `trace_dylib_resolution` - Trace dynamic library resolution\n\n## .NET Inspection\n\n- `inspect_managed_artifact` - Inspect managed artifact\n- `inspect_managed_members` - Inspect managed members\n- `inspect_managed_native_boundaries` - Inspect managed/native boundaries\n- `compare_managed_members` - Compare managed members\n- `verify_managed_native_boundaries` - Verify managed/native boundaries\n- `import_managed_reconstruction` - Import managed reconstruction\n- `project_managed_application_graph` - Project managed app graph\n\n## Disassembly Tools\n\n- `inspect_with_objdump` - Inspect with objdump\n- `execute_rizin_command` - Execute Rizin command\n\n## Frida Instrumentation\n\n- `list_frida_devices` - List Frida devices\n- `list_frida_processes` - List Frida processes\n- `start_frida_session` - Start Frida session\n- `load_frida_script` - Load Frida script\n- `resume_frida_session` - Resume Frida session\n- `unload_frida_script` - Unload Frida script\n- `frida_session_status` - Get Frida session status\n- `close_frida_session` - Close Frida session\n- `instrument_with_frida` - Instrument with Frida\n\n## Cutter Integration\n\n- `list_cutter_sessions` - List Cutter sessions\n- `cutter_command` - Execute Cutter command\n\n## GDB Integration\n\n- `start_gdb_session` - Start GDB session\n- `gdb_console` - GDB console\n- `gdb_session_status` - GDB session status\n- `close_gdb_session` - Close GDB session\n\n## Rizin Debugging\n\n- `start_rizin_debug_session` - Start Rizin debug session\n- `rizin_debug_command` - Rizin debug command\n- `rizin_debug_session_status` - Rizin debug session status\n- `close_rizin_debug_session` - Close Rizin debug session\n\n## Packet Capture\n\n- `inspect_packet_capture` - Inspect packet capture\n- `inspect_packet_fields` - Inspect packet fields\n\n## Signal Processing\n\n- `decode_signal_recording` - Decode signal recording\n- `inspect_signal_decoders` - Inspect signal decoders\n\n## Android Tools\n\n- `inspect_android_package` - Inspect Android package\n- `search_android_classes` - Search Android classes\n- `inspect_android_class` - Inspect Android class\n- `inspect_android_method` - Inspect Android method\n- `trace_android_references` - Trace Android references\n- `inspect_jeb_client` - Inspect Jeb client\n- `open_jeb_project` - Open Jeb project\n- `list_jeb_units` - List Jeb units\n- `decompile_jeb_item` - Decompile Jeb item\n\n## Binary Analysis\n\n- `inspect_byte_entropy` - Inspect byte entropy\n- `inspect_binary_layout` - Inspect binary layout\n- `inspect_analysis_view` - Inspect analysis view\n- `inspect_pe_resources` - Inspect PE resources\n- `inspect_native_exports` - Inspect native exports\n- `inspect_devicetree` - Inspect device tree\n- `inspect_elf_notes` - Inspect ELF notes\n\n## Apple Platforms\n\n- `inspect_apple_ips` - Inspect Apple IPS\n- `inspect_kernel_module` - Inspect kernel module\n- `inspect_pe_load_config` - Inspect PE load config\n- `inspect_pe_certificates` - Inspect PE certificates\n- `inspect_pe_imports` - Inspect PE imports\n- `inspect_go_binary` - Inspect Go binary\n- `inspect_il2cpp_metadata` - Inspect IL2CPP metadata\n- `inspect_rust_binary` - Inspect Rust binary\n- `inspect_python_pyc` - Inspect Python pyc\n- `inspect_erlang_beam` - Inspect Erlang BEAM\n\n## WebAssembly\n\n- `compare_wasm_interfaces` - Compare Wasm interfaces\n- `inspect_wasm_artifact` - Inspect Wasm artifact\n\n## Android APK\n\n- `inspect_apk_signature` - Inspect APK signature\n\n## EVM\n\n- `inspect_evm_interface` - Inspect EVM interface\n\n## SQLite\n\n- `inspect_sqlite_database` - Inspect SQLite database\n\n## Crash Analysis\n\n- `inspect_recorded_crash` - Inspect recorded crash\n\n## Firmware\n\n- `inspect_firmware_regions` - Inspect firmware regions\n- `extract_firmware` - Extract firmware\n\n## Web Analysis\n\n- `trace_web_module_imports` - Trace web module imports\n- `trace_web_source_location` - Trace web source location\n- `observe_web_execution` - Observe web execution\n- `inspect_web_event_listeners` - Inspect web event listeners\n- `inspect_web_network_capture` - Inspect web network capture\n- `recover_javascript_sources` - Recover JavaScript sources\n\n## PCAP\n\n- `inspect_pcap_client` - Inspect PCAP client\n- `inspect_pcap_capture` - Inspect PCAP capture\n\n## Flutter\n\n- `identify_flutter_build` - Identify Flutter build\n- `inspect_dart_aot` - Inspect Dart AOT\n\n## APKTool\n\n- `inspect_apktool_client` - Inspect APKTool client\n- `decode_android_resources` - Decode Android resources\n\n## Web Export\n\n- `export_web_scripts` - Export web scripts\n\n## Browser\n\n- `list_browser_targets` - List browser targets\n- `inspect_web_page` - Inspect web page\n- `analyze_web_bundle` - Analyze web bundle\n- `observe_web_session` - Observe web session\n- `discover_webmcp_tools` - Discover WebMCP tools\n- `compare_web_captures` - Compare web captures\n- `capture_web_screenshot` - Capture web screenshot\n- `compare_web_screenshots` - Compare web screenshots\n- `capture_browser_scenario` - Capture browser scenario\n\n## Electron\n\n- `inspect_electron_fuses` - Inspect Electron fuses\n- `list_electron_targets` - List Electron targets\n- `inspect_electron_page` - Inspect Electron page\n- `analyze_javascript_application` - Analyze JS application\n- `reconcile_javascript_runtime` - Reconcile JS runtime\n- `capture_electron_scenario` - Capture Electron scenario\n- `list_javascript_runtime_targets` - List JS runtime targets\n- `observe_javascript_runtime` - Observe JS runtime\n\n## Application Analysis\n\n- `trace_application_feature` - Trace application feature\n- `trace_javascript_semantics` - Trace JS semantics\n- `compare_application_versions` - Compare app versions\n- `compare_source_to_bundle` - Compare source to bundle\n- `compare_javascript_export_shapes` - Compare JS export shapes\n\n## Reconstruction\n\n- `build_reconstruction_obligation_ledger` - Build reconstruction ledger\n- `evaluate_reconstruction_coverage` - Evaluate reconstruction coverage\n- `export_environment_bundle` - Export environment bundle\n- `project_android_application_graph` - Project Android app graph\n- `project_apple_application_graph` - Project Apple app graph\n- `project_harmony_application_graph` - Project Harmony app graph\n- `open_bundle_resources` - Open bundle resources\n- `open_binary` - Open binary\n- `close_binary` - Close binary\n- `binary_session` - Get binary session\n- `export_evidence_bundle` - Export evidence bundle\n- `import_evidence_bundle` - Import evidence bundle\n- `get_evidence_bundle` - Get evidence bundle\n- `capture_process_scenario` - Capture process scenario\n- `compare_process_captures` - Compare process captures\n- `compare_artifacts` - Compare artifacts\n- `compare_functions` - Compare functions\n- `match_native_functions` - Match native functions\n- `compare_bundles` - Compare bundles\n- `find_changed_behavior` - Find changed behavior\n- `build_call_path` - Build call path\n- `correlate_static_and_runtime` - Correlate static and runtime\n- `verify_reconstruction` - Verify reconstruction\n\n## Unknowns Tracking\n\n- `list_unknowns` - List unknowns\n- `record_unknown` - Record unknown\n- `update_unknown` - Update unknown\n- `verify_unknown_resolution` - Verify unknown resolution\n\n## Context\n\n- `get_navigation_context` - Get navigation context\n- `inspect_address_context` - Inspect address context"

    private fun p(
        name: String,
        type: String = "string",
        description: String = "",
        required: Boolean = false
    ): ToolParameterProperty = ToolParameterProperty(
        name = name,
        type = type,
        description = description,
        required = required
    )

    private fun t(
        serverId: String,
        name: String,
        description: String,
        vararg params: ToolParameterProperty
    ): McpRemoteTool = McpRemoteTool(
        serverId = serverId,
        name = name,
        description = description,
        parameters = params.toList()
    )

    fun buildDefaultReaTools(serverId: String = "rea_mcp"): List<McpRemoteTool> = listOf(
        t(serverId, "inspect_analysis_settings", "[Binary Analysis] Inspect current analysis settings. Inspect REA's editable runtime admission sizes, deadlines and worker heaps, their bounds, launch values and application points", p("settings", "array", "", false), p("operation", "string", "", false), p("provider", "string", "", false)),
        t(serverId, "update_analysis_settings", "[Binary Analysis] Update analysis settings. When REA names an adjustable limit, atomically set or reset its runtime default, then retry once work and cleanup have settled", p("changes", "array", "", true), p("expected_revision", "integer", "", false)),
        t(serverId, "inspect_desktop_application", "[Application Analysis] Inspect a local desktop application directory (.NET, Unity, Electron, Unreal, Godot). Inspect a local desktop application directory using bounded listings, file headers and selected metadata", p("path", "string", "Local filesystem path used by this operation.", true), p("max_entries", "integer", "Maximum permitted entries for this operation.", false), p("max_probe_bytes", "integer", "Maximum permitted probe bytes for this operation.", false), p("detail", "string", "", false)),
        t(serverId, "inspect_model_artifact", "[Binary Format Inspection] Inspect local GGUF or safetensors AI model artifacts. Inspect local GGUF v2/v3 or safetensors declarations by content, without loading models or executing code", p("path", "string", "Local filesystem path used by this operation.", true), p("hash", "string", "header reads declarations only; full streams all bytes for SHA-256", false), p("detail", "string", "", false), p("view", "object", "Return this selected page with compact model context, retaining complete Evidence; requires summary detail", false)),
        t(serverId, "inspect_gpu_code_objects", "[Binary Format Inspection] Inspect static CUDA/HIP GPU code objects (fatbins, CUBIN, PTX). Inspect static CUDA/HIP GPU code objects in local ELF/PE hosts, CUDA fatbins, CUBIN, PTX and Clang HIP bundles (CUDA LZ4/Zstd and CCOB v1/v2/v3 zlib/Zstd)", p("path", "string", "Local filesystem path used by this operation.", true), p("analysis", "string", "", false), p("expected_sha256", "string", "Require the current full-file digest; mandatory when selecting inventory object_ids", false), p("object_ids", "array", "Decode only these inventory IDs; IDs are scoped to the artifact SHA-256", false), p("targets", "array", "Decode only exact target declarations, preserving all inventory rows", false), p("detail", "string", "", false), p("view", "object", "", false)),
        t(serverId, "annotate_native_function", "[Comments and Annotations] Annotate native function. Atomically update one function name and/or entry comments, read them back, and return a refreshed complete function dossier", p("procedure", "string", "", true), p("name", "string", "", false), p("comment", "string", "", false), p("inline_comment", "string", "", false)),
        t(serverId, "inspect_native_load_image", "[Strings and Data Types] Inspect native load image"),
        t(serverId, "inspect_native_data_type", "[Strings and Data Types] Inspect native data type. Inspect one recovered type by exact database category path or defined typed data address", p("type", "string", "", false), p("address", "string", "", false), p("document", "string", "", false)),
        t(serverId, "inspect_native_instruction", "[Strings and Data Types] Inspect native instruction. Inspect one exact code or data address: decoded bytes, mnemonic, ordered operand tokens, flow, outgoing instruction references and typed incoming references where available", p("address", "string", "", true), p("document", "string", "", false)),
        t(serverId, "resolve_native_call_targets", "[Functions and Calls] Resolve call targets. Resolve one explicit static call site using typed provider call references", p("address", "string", "", true), p("document", "string", "", false)),
        t(serverId, "address_name", "[Memory and Data] Get address name. Resolve the primary analyzed name at a code or data address", p("document", "string", "", false), p("address", "string", "", false)),
        t(serverId, "comment", "[Comments and Annotations] Add comment. Read the regular analysis comment at an address, defaulting to the current cursor", p("document", "string", "", false), p("address", "string", "", false)),
        t(serverId, "current_address", "[Binary Analysis] Get current address. Return Hopper's current cursor address for the selected document", p("document", "string", "", false)),
        t(serverId, "current_procedure", "[Binary Analysis] Get current procedure. Return the analyzed procedure containing Hopper's current cursor", p("document", "string", "", false)),
        t(serverId, "current_document", "[Binary Analysis] Get current document. Return the document currently selected by REA's Hopper bridge."),
        t(serverId, "goto_address", "[Binary Analysis] Navigate to address. Move Hopper's GUI cursor to a mapped address and return the observed cursor address", p("address", "string", "", true), p("document", "string", "", false)),
        t(serverId, "inline_comment", "[Comments and Annotations] Add inline comment. Read the inline instruction comment at an address, defaulting to the current cursor", p("document", "string", "", false), p("address", "string", "", false)),
        t(serverId, "list_bookmarks", "[Bookmarks] List all bookmarks. List every bookmark in the selected Hopper document as address and name pairs; unnamed bookmarks retain a null name", p("document", "string", "", false)),
        t(serverId, "list_documents", "[Binary Analysis] List all documents. List provider program or document identities"),
        t(serverId, "list_names", "[Memory and Data] List all names in binary. List every analyzed memory and external symbol as address/value pairs", p("document", "string", "", false), p("address", "string", "", false), p("detail", "string", "", false)),
        t(serverId, "list_procedures", "[Functions and Calls] List all procedures. List every analyzed procedure as address/value pairs after provider analysis", p("document", "string", "", false), p("detail", "string", "", false)),
        t(serverId, "list_segments", "[Strings and Data Types] List all segments. List segments or memory blocks using exclusive end addresses", p("document", "string", "", false)),
        t(serverId, "list_strings", "[Strings and Data Types] List all strings. List every provider-reported string object or candidate, or filter to one address, as address/value pairs with encoding, terminator status, and byte length when available", p("document", "string", "", false), p("address", "string", "", false), p("detail", "string", "", false)),
        t(serverId, "next_address", "[Binary Analysis] Get next address. Return the next analyzed object address after an explicit address or current cursor", p("document", "string", "", false), p("address", "string", "", false)),
        t(serverId, "prev_address", "[Binary Analysis] Get previous address. Return the previous analyzed object start before an explicit address or current cursor", p("document", "string", "", false), p("address", "string", "", false)),
        t(serverId, "procedure_address", "[Binary Analysis] Get procedure address. Resolve an unambiguous procedure symbol name or provider-normalized address to its canonical entry address", p("procedure", "string", "", true), p("document", "string", "", false)),
        t(serverId, "procedure_assembly", "[Binary Analysis] Get procedure assembly. Return assembly for one analyzed procedure identified by symbol or hexadecimal address", p("procedure", "string", "", true), p("document", "string", "", false)),
        t(serverId, "procedure_callees", "[Functions and Calls] Get function callees. Return the provider's resolved direct callees for one procedure identified by symbol or address", p("procedure", "string", "", true), p("document", "string", "", false)),
        t(serverId, "procedure_callers", "[Functions and Calls] Get function callers. Return the provider's resolved direct callers for one procedure identified by symbol or address", p("procedure", "string", "", true), p("document", "string", "", false)),
        t(serverId, "procedure_info", "[Binary Analysis] Get procedure information. Return provider metadata for one procedure identified by symbol or address: entrypoint, signature, locals, size, block count, and complete inclusive body ranges when observed", p("procedure", "string", "", true), p("document", "string", "", false)),
        t(serverId, "read_function_instructions", "[Binary Analysis] Read function instructions. Return every raw instruction for one analyzed procedure", p("procedure", "string", "", true), p("document", "string", "", false)),
        t(serverId, "search_bytes", "[Memory and Data] Search for byte patterns. Find masked byte signatures in initialized analyzed memory", p("pattern", "string", "", true), p("mask", "string", "", false), p("document", "string", "", false), p("start", "string", "", false), p("end", "string", "", false), p("limit", "integer", "", false), p("cursor", "string", "", false)),
        t(serverId, "read_bytes", "[Memory and Data] Read bytes from memory. Read analyzed bytes from one provider-normalized virtual address", p("address", "string", "", true), p("length", "integer", "", false), p("document", "string", "", false)),
        t(serverId, "address_to_file_offset", "[Memory and Data] Convert address to file offset. Map one provider-normalized virtual address to its original nonnegative file offset", p("address", "string", "", true), p("document", "string", "", false)),
        t(serverId, "procedure_references", "[Functions and Calls] Get procedure references. Return every raw incoming or outgoing reference edge for one procedure", p("procedure", "string", "", true), p("direction", "string", "", false), p("document", "string", "", false)),
        t(serverId, "procedure_pseudo_code", "[Binary Analysis] Get procedure pseudo-code. Decompile one analyzed procedure by symbol name or provider-normalized address", p("procedure", "string", "", true), p("document", "string", "", false)),
        t(serverId, "resolve_containing_procedure", "[Functions and Calls] Resolve containing procedure. Resolve an arbitrary address, including an interior instruction or exact external entry, to its provider-analyzed containing procedure", p("address", "string", "", true), p("document", "string", "", false)),
        t(serverId, "search_procedures", "[Functions and Calls] Search procedures. Search every analyzed procedure name using literal matching by default or regex when requested", p("pattern", "string", "", true), p("mode", "string", "", false), p("case_sensitive", "boolean", "", false), p("document", "string", "", false), p("detail", "string", "", false)),
        t(serverId, "search_strings", "[Strings and Data Types] Search for strings. Search every provider-reported string object or candidate using literal matching by default or regex when requested", p("pattern", "string", "", true), p("mode", "string", "", false), p("case_sensitive", "boolean", "", false), p("document", "string", "", false), p("detail", "string", "", false)),
        t(serverId, "set_address_name", "[Memory and Data] Set name at address. Assign an analyst name to one mapped hexadecimal address and report whether the native edit succeeded and exact readback matched", p("address", "string", "", true), p("name", "string", "", true), p("document", "string", "", false)),
        t(serverId, "set_addresses_names", "[Memory and Data] Set multiple names. Assign analyst names to multiple mapped addresses in one call and return per-address edit/readback success booleans", p("names", "object", "", true), p("document", "string", "", false)),
        t(serverId, "set_bookmark", "[Bookmarks] Set bookmark. Create or update a bookmark at a mapped hexadecimal address and report readback success", p("address", "string", "", true), p("name", "string", "", false), p("document", "string", "", false)),
        t(serverId, "set_comment", "[Comments and Annotations] Set comment at address. Write a regular analysis comment at a hexadecimal address and return whether readback matched", p("address", "string", "", true), p("comment", "string", "", true), p("document", "string", "", false)),
        t(serverId, "set_inline_comment", "[Comments and Annotations] Set inline comment. Write an inline instruction comment at a hexadecimal address and return whether readback matched", p("address", "string", "", true), p("comment", "string", "", true), p("document", "string", "", false)),
        t(serverId, "unset_bookmark", "[Bookmarks] Remove bookmark. Remove the bookmark at a mapped hexadecimal address and return whether it is absent", p("address", "string", "", true), p("document", "string", "", false)),
        t(serverId, "xrefs", "[Cross References] Get cross references. Return analyzed references to a code or data address", p("document", "string", "", false), p("address", "string", "", false)),
        t(serverId, "trace_native_values", "[Tracing and Runtime Analysis] Trace native values. Trace a bounded static dependency graph from one explicit native procedure", p("procedure", "string", "", true), p("max_depth", "integer", "", false), p("max_functions", "integer", "", false), p("max_call_sites", "integer", "", false), p("max_nodes", "integer", "", false), p("max_edges", "integer", "", false), p("offset", "integer", "", false), p("limit", "integer", "", false)),
        t(serverId, "inspect_native_dispatch_metadata", "[Tracing and Runtime Analysis] Inspect dispatch metadata. Inspect the bound provider's name inventory for Objective-C class/method symbols and Swift mangled symbols, returning at most max_records decoded names", p("max_records", "integer", "", false), p("objc_class_import_addresses", "array", "", false), p("objc_class_import_addresses_complete", "boolean", "", false), p("max_input_bytes", "integer", "", false)),
        t(serverId, "get_objc_classes", "[iOS/macOS Tools] List Objective-C classes. Discover Objective-C class labels, optionally filtering by literal substring", p("pattern", "string", "", false)),
        t(serverId, "get_objc_protocols", "[iOS/macOS Tools] List Objective-C protocols. Discover and deduplicate Objective-C and Swift protocol labels."),
        t(serverId, "batch_decompile", "[Decompilation] Batch decompile functions. Decompile each explicit procedure symbol or address, with one provider read active per batch", p("addresses", "array", "", false)),
        t(serverId, "decompile_all", "[Decompilation] Decompile all functions. Write provider pseudocode for every analyzed function, or a filtered subset, into a caller-selected directory and return only a manifest", p("output_directory", "string", "Absolute output directory. The parent must exist. The directory itself must be absent unless resume is true. One run at a time may use a dir", true), p("layout", "string", "address names files by canonical address. dwarf asks for compilation-unit paths and is reported unavailable when the provider has no such fa", false), p("resume", "boolean", "Continue a journal for the same target SHA-256, provider, analysis profile, and layout. Both digests must be known. Verified files and funct", false), p("name", "string", "Case-sensitive literal substring of the provider function name.", false), p("namespace", "string", "Case-sensitive prefix of the provider function name, including any separator such as Foo:: or Class.", false), p("address_start", "string", "Inclusive lower bound as 0xoffset or space:0xoffset. Must use the same address space as address_end.", false), p("address_end", "string", "Inclusive upper bound as 0xoffset or space:0xoffset. Must use the same address space as address_start.", false), p("limit", "integer", "Maximum functions to attempt on this call after skipping verified files and functions recorded as external. Further matches remain for a lat", false)),
        t(serverId, "get_call_graph", "[Decompilation] Get call graph. Resolve one symbol or address to its canonical procedure entry, then traverse the bound provider's caller or callee relationships until the reachable graph is exhausted", p("address", "string", "", true), p("direction", "string", "", false)),
        t(serverId, "analyze_swift_types", "[iOS/macOS Tools] Analyze Swift types and metadata descriptors. Categorize analyzed Swift procedure names using their manglings or same-address Swift symbol aliases", p("category", "string", "", false), p("pattern", "string", "", false)),
        t(serverId, "find_xrefs_to_name", "[Cross References] Find cross references to name. Resolve an exact name against the bound provider's name inventory and return a resolved or unresolved result", p("name", "string", "", true)),
        t(serverId, "binary_overview", "[Binary Analysis] Get comprehensive binary overview and metadata summary. Return the bound document and exhaustive procedure/string counts, plus every segment and segment_count when the provider supports segment enumeration"),
        t(serverId, "analyze_function", "[Decompilation] Analyze function. Build a dossier for one native function identified by symbol or provider-returned address", p("procedure", "string", "", true), p("detail", "string", "", false)),
        t(serverId, "inspect_native_api", "[Binary Analysis] Inspect native API imports, exports, and symbol bindings. Analyze a native API boundary in one function identified by symbol or provider-returned address", p("procedure", "string", "", true)),
        t(serverId, "trace_feature", "[Tracing and Runtime Analysis] Trace feature execution. Trace the complete literal query, preserving spaces, through every matching string and procedure, their xrefs, and truthful containing-procedure resolution", p("query", "string", "", true), p("case_sensitive", "boolean", "", false)),
        t(serverId, "trace_call_path", "[Tracing and Runtime Analysis] Trace call path. Resolve start and optional goal symbols or addresses to canonical procedure entries, then trace direct callers or callees until the graph is exhausted or the goal is reached", p("start", "string", "", true), p("goal", "string", "", false), p("direction", "string", "", false)),
        t(serverId, "trace_native_ui_action", "[Tracing and Runtime Analysis] Trace UI action. Trace one unique compiled UI action, object ID, native symbol or exact function address through authored connections, encoded or symbolized Objective-C handlers, and bounded static call references", p("action", "string", "", true), p("max_depth", "integer", "", false), p("max_nodes", "integer", "", false), p("max_edges", "integer", "", false)),
        t(serverId, "observe_native_ui", "[Tracing and Runtime Analysis] Observe native UI. Observe an already-running native app by PID and window ID, bound to the active Mach-O target", p("pid", "integer", "", true), p("window_id", "integer", "", true), p("screenshot", "boolean", "", false), p("accessibility", "boolean", "", false), p("max_nodes", "integer", "", false), p("native_ui_deadline_ms", "integer", "", false)),
        t(serverId, "capture_native_ui_scenario", "[Tracing and Runtime Analysis] Capture UI scenario. Run selected-element press, increment/decrement scroll, AXValue text entry and bounded wait steps in one exact native app window", p("pid", "integer", "", true), p("window_id", "integer", "", true), p("screenshot", "boolean", "", false), p("accessibility", "boolean", "", false), p("max_nodes", "integer", "Maximum accessibility nodes per capture; increase for large windows.", false), p("native_ui_deadline_ms", "integer", "Deadline in milliseconds for this UI observation or scenario, including helper preparation and captures. Omitted means 180000. Integer from ", false), p("steps", "array", "", true)),
        t(serverId, "observe_native_calls", "[Tracing and Runtime Analysis] Observe native calls", p("breakpoints", "array", "", true), p("arguments", "array", "", false), p("environment", "object", "Overrides on top of the environment REA runs with.", false), p("working_directory", "string", "", false), p("duration_ms", "integer", "Observation window; the process is killed if it is still running.", false), p("max_events", "integer", "Stop observing and kill the process after this many recorded entries.", false), p("argument_registers", "integer", "Integer argument registers to record per entry (arm64 has 8, x86_64 has 6).", false), p("backtrace_frames", "integer", "Caller frames to record per entry.", false)),
        t(serverId, "inspect_macho", "[Binary Format Inspection] Inspect Mach-O binaries. Inspect Mach-O slices, load commands, imports, exports, dependencies, build metadata, segments, sections, permissions, and exact command provenance without launching Hopper", p("detail", "string", "", false), p("native_command_timeout_ms", "integer", "", false)),
        t(serverId, "inspect_signature", "[Binary Format Inspection] Inspect binary signature. Inspect the selected Mach-O architecture's code-signing identity, hashes, authorities, designated requirement, entitlements and hardened-runtime state from a temporary digest-verified byte snapshot", p("native_command_timeout_ms", "integer", "", false)),
        t(serverId, "inspect_plist", "[Binary Format Inspection] Inspect plist files. Requires an open binary or bundle-resource session; use open_binary or open_bundle_resources first", p("path", "string", "", false), p("native_command_timeout_ms", "integer", "", false)),
        t(serverId, "list_architectures", "[Binary Format Inspection] List architectures in binary. List thin or universal Mach-O slices with offsets, sizes, alignment, explicit coverage, and native-tool provenance", p("native_command_timeout_ms", "integer", "", false)),
        t(serverId, "demangle_swift", "[Binary Format Inspection] Demangle Swift symbols. Demangle an ordered list of single-line Swift symbols without requiring Hopper", p("symbols", "array", "Ordered Swift symbols to demangle.", true), p("native_command_timeout_ms", "integer", "Wall-clock budget in milliseconds for each macOS native command in this call (otool, lipo, codesign, plutil, swift-demangle, and the other a", false)),
        t(serverId, "inspect_artifact", "[Binary Format Inspection] Inspect artifact. Inspect an active archive or application package, including Node.js SEA carriers, in one call", p("integrity_policy", "string", "", false), p("detail", "string", "", false)),
        t(serverId, "extract_artifact", "[Binary Format Inspection] Extract artifact. Extract regular files (including Node.js SEA scripts and assets) exposed by a supported active archive reader into a fresh temporary directory chosen by REA", p("integrity_policy", "string", "", false)),
        t(serverId, "decode_interface_builder", "[iOS/macOS Tools] Decode Interface Builder files. Decode compiled storyboard and nib archives inside the active Apple app bundle, including keyed property lists and NIBArchive object tables, into a bounded object, hierarchy, and connection graph", p("max_documents", "integer", "", false), p("max_objects", "integer", "", false), p("max_connections", "integer", "", false), p("detail", "string", "", false)),
        t(serverId, "inspect_keyed_archive", "[iOS/macOS Tools] inspect Keyed Archive. Inspect one Foundation NSKeyedArchiver plist in the active app bundle as original object-table nodes, class descriptors, named roots, and UID edges", p("path", "string", "", false), p("root", "string", "", false), p("offset", "integer", "", false), p("limit", "integer", "", false)),
        t(serverId, "inspect_asset_catalog", "[iOS/macOS Tools] Inspect asset catalog. Inspect compiled Assets.car metadata in an active Apple app bundle", p("offset", "integer", "", false), p("limit", "integer", "", false)),
        t(serverId, "trace_dylib_resolution", "[iOS/macOS Tools] Trace dynamic library resolution. Trace how dyld would resolve each Mach-O dylib load for the active Mach-O, or for every executable in the active .app bundle (the main app, XPC services, extensions, login items, helpers)", p("roots", "array", "", false), p("architecture", "string", "", false)),
        t(serverId, "inspect_managed_artifact", "[.NET Inspection] Inspect managed artifact. Inspect an explicit PE/CLI path, or the selected managed target, without loading or executing target code", p("path", "string", "", false), p("detail", "string", "", false)),
        t(serverId, "inspect_managed_members", "[.NET Inspection] Inspect managed members", p("path", "string", "", false), p("detail", "string", "", false), p("type_names", "array", "", false)),
        t(serverId, "inspect_managed_native_boundaries", "[.NET Inspection] Inspect managed/native boundaries. Inspect PE/CLI ModuleRef, ImplMap/PInvoke declarations, CLI native-header indicators, and non-IL method implementation flags without loading or executing target code", p("path", "string", "", false)),
        t(serverId, "compare_managed_members", "[.NET Inspection] Compare managed members", p("left", "object", "Left-hand input used for comparison or differential execution.", true), p("right", "object", "Right-hand input used for comparison or differential execution.", true)),
        t(serverId, "verify_managed_native_boundaries", "[.NET Inspection] Verify managed/native boundaries. Verify managed P/Invoke/native-boundary declarations against authenticated native export or function Evidence without executing managed code or translating managed metadata tokens into native addresses", p("managed_boundaries", "object", "", true), p("native_observations", "array", "Native function or export Evidence records", true)),
        t(serverId, "import_managed_reconstruction", "[.NET Inspection] Import managed reconstruction. Import decompiler-produced managed reconstruction against authenticated inspect_managed_members Evidence", p("static_members", "object", "", true), p("decompiler", "object", "", true), p("methods", "array", "", true), p("notes", "array", "", false)),
        t(serverId, "project_managed_application_graph", "[.NET Inspection] Project managed app graph", p("managed_artifact", "object", "", false), p("managed_members", "object", "", false), p("managed_native_boundaries", "object", "", false)),
        t(serverId, "inspect_with_objdump", "[Disassembly Tools] Inspect with objdump. Run caller-selected GNU objdump inspection against a local artifact, or request operation=architectures without a path to list BFD targets advertised by the installed binary", p("path", "string", "", false), p("operation", "string", "", true), p("follow_debug_links", "boolean", "", false), p("command_timeout_ms", "integer", "", false), p("detail", "string", "", false), p("symbol", "string", "", false)),
        t(serverId, "execute_rizin_command", "[Disassembly Tools] Execute Rizin command. Execute one caller-selected Rizin command against a local artifact using the Rizin CLI profile that suppresses user settings and scripts while retaining plugins", p("path", "string", "", true), p("command", "string", "", true), p("command_timeout_ms", "integer", "", false)),
        t(serverId, "list_frida_devices", "[Frida Instrumentation] List Frida devices", p("remote", "object", "", false)),
        t(serverId, "list_frida_processes", "[Frida Instrumentation] List Frida processes. List processes observed on a selected Frida device or caller-supplied remote endpoint", p("device_id", "string", "", false), p("remote", "object", "", false)),
        t(serverId, "start_frida_session", "[Frida Instrumentation] Start Frida session. Attach Frida to one selected process or spawn one selected target", p("mode", "string", "", true), p("device_id", "string", "", false), p("remote", "object", "", false), p("pid", "integer", "", false), p("program", "string", "", false), p("argv", "array", "", false)),
        t(serverId, "load_frida_script", "[Frida Instrumentation] Load Frida script. Load caller-supplied JavaScript into a Frida session", p("session_id", "string", "", true), p("source_kind", "string", "", true), p("source", "string", "", false), p("path", "string", "", false)),
        t(serverId, "resume_frida_session", "[Frida Instrumentation] Resume Frida session. Resume the process created by a paused Frida spawn session", p("session_id", "string", "", true)),
        t(serverId, "unload_frida_script", "[Frida Instrumentation] Unload Frida script. Unload one caller-selected Frida script from a live session", p("session_id", "string", "", true), p("script_id", "string", "", true)),
        t(serverId, "frida_session_status", "[Frida Instrumentation] Get Frida session status. Read observed Frida session state on each call", p("session_id", "string", "", true), p("detail", "string", "", false)),
        t(serverId, "close_frida_session", "[Frida Instrumentation] Close Frida session. Unload session scripts and attempt to detach REA's Frida session", p("session_id", "string", "", true)),
        t(serverId, "instrument_with_frida", "[Frida Instrumentation] Instrument with Frida. Run one caller-authored Frida script against an explicitly selected process and detach when the observation window ends", p("mode", "string", "", true), p("device_id", "string", "", false), p("remote", "object", "", false), p("pid", "integer", "", false), p("program", "string", "", false), p("argv", "array", "", false), p("source_kind", "string", "", true), p("source", "string", "", false), p("path", "string", "", false), p("duration_ms", "integer", "", false)),
        t(serverId, "list_cutter_sessions", "[Cutter Integration] List Cutter sessions. List live REA bridge sessions hosted by upstream Cutter instances, including observed document identity and bridge-directory security status"),
        t(serverId, "cutter_command", "[Cutter Integration] Execute Cutter command. Run one arbitrary Rizin command in a selected live Cutter session", p("session_id", "string", "", true), p("expected_generation", "integer", "", true), p("command", "string", "", true), p("json", "boolean", "", false)),
        t(serverId, "start_gdb_session", "[GDB Integration] Start GDB session. Start a BYO GDB process in MI3 mode when available, suppress startup files, and disable auto-loading", p("inferior_tty", "string", "", false)),
        t(serverId, "gdb_console", "[GDB Integration] GDB console. Run one unrestricted GDB CLI command through MI interpreter-exec console and return raw MI, console, target, and log records inline with Evidence", p("session_id", "string", "", true), p("command", "string", "", true), p("detail", "string", "", false)),
        t(serverId, "gdb_session_status", "[GDB Integration] GDB session status. Return the observed lifecycle state and bounded recent MI records and process diagnostics for one persistent REA GDB session; truncation and invalid-UTF-8 fields report discarded or lossy data.", p("session_id", "string", "", true)),
        t(serverId, "close_gdb_session", "[GDB Integration] Close GDB session. Close the owned GDB process after verifying the inferior state", p("session_id", "string", "", true)),
        t(serverId, "start_rizin_debug_session", "[Rizin Debugging] Start Rizin debug session. Start a persistent Rizin debugger session using `-N` to suppress user settings and scripts while retaining plugins, `-0` for NUL framing, and `-d` with an optional caller-selected IO debugger backend", p("path", "string", "", true), p("inferior_tty", "string", "", true), p("backend", "string", "", false)),
        t(serverId, "rizin_debug_command", "[Rizin Debugging] Rizin debug command. Execute one unrestricted command in a persistent Rizin debugger session", p("session_id", "string", "", true), p("command", "string", "", true)),
        t(serverId, "rizin_debug_session_status", "[Rizin Debugging] Rizin debug session status", p("session_id", "string", "", true)),
        t(serverId, "close_rizin_debug_session", "[Rizin Debugging] Close Rizin debug session. Close the REA-owned Rizin debugger only after verifying no inferior remains or detaching one with a verified caller-selected TTY output sink", p("session_id", "string", "", true)),
        t(serverId, "inspect_packet_capture", "[Packet Capture] Inspect packet capture. Inspect caller-selected fields in an existing local packet capture with TShark 4.x", p("path", "string", "Absolute local capture path", true), p("fields", "array", "TShark field names to return, e.g. ip.addr, usb.endpoint_address, btatt.opcode; frame.number is always included", true), p("display_filter", "string", "Optional Wireshark display filter (-Y), applied within the read limit", false), p("decode_as", "array", "Explicit TShark decode-as selections, e.g. tcp.port==8888,http", false), p("packet_limit", "string", "Maximum input packets to read before filtering; omitted scans to EOF within resource limits", false), p("two_pass", "boolean", "Use two-pass analysis for fields requiring later packets; false requests a single pass", false), p("match_offset", "integer", "Skip this many matching packets after filtering; default 0", false), p("match_limit", "string", "Return at most this many matching packets after filtering; default 50. Null explicitly returns all remaining matches within producer resourc", false)),
        t(serverId, "inspect_packet_fields", "[Packet Capture] Inspect packet fields. Discover protocols or field names/types registered in the installed TShark 4.x", p("kind", "string", "", false), p("protocol", "string", "", false), p("query", "string", "", false), p("offset", "integer", "", false), p("limit", "string", "", false)),
        t(serverId, "decode_signal_recording", "[Signal Processing] Decode signal recording. Decode an existing local logic/signal recording on Linux with sigrok-cli 0.7.2 and util-linux prlimit", p("path", "string", "Absolute local recording path", true), p("decoders", "string", "sigrok decoder stack, options and channel assignments, e.g. uart:rx=RX:baudrate=9600", true), p("input_format", "string", "Explicit sigrok input format and options; omitted means autodetection", false), p("annotations", "string", "Optional annotation selection, e.g. uart=rx-data", false), p("line_offset", "integer", "Skip this many literal LF-separated output lines; default 0", false), p("line_limit", "string", "Return at most this many output lines; default 50. Null explicitly returns all remaining lines within producer limits. Lines are not annotat", false)),
        t(serverId, "inspect_signal_decoders", "[Signal Processing] Inspect signal decoders. Discover installed sigrok protocol decoders or inspect one decoder's channel/option help on Linux", p("decoder", "string", "", false), p("query", "string", "", false), p("offset", "integer", "", false), p("limit", "string", "", false)),
        t(serverId, "inspect_android_package", "[Android Tools] Inspect Android package. Inspect a local APK's package, SDK and permission declarations, decoded manifest and class/resource counts, with a bounded static component projection", p("path", "string", "", true), p("detail", "string", "", false)),
        t(serverId, "search_android_classes", "[Android Tools] Search Android classes. Search complete top-level decompiler class-name rows in an explicit local APK by case-sensitive substring", p("path", "string", "", true), p("query", "string", "", true)),
        t(serverId, "inspect_android_class", "[Android Tools] Inspect Android class. Inspect one exact Android class's parsed methods, fields and inner classes, including synthetic members", p("path", "string", "", true), p("class_name", "string", "", true)),
        t(serverId, "inspect_android_method", "[Android Tools] Inspect Android method. Decompile one Android method with smali fallback", p("path", "string", "", true), p("class_name", "string", "", true), p("method_name", "string", "", true), p("overload_index", "integer", "", false)),
        t(serverId, "trace_android_references", "[Android Tools] Trace Android references. Trace incoming static using nodes to an exact Android class or selected method overload", p("path", "string", "", true), p("class_name", "string", "", true), p("method_name", "string", "", false), p("overload_index", "integer", "", false)),
        t(serverId, "inspect_jeb_client", "[Android Tools] Inspect Jeb client. Inspect the running JEB client serving MCP: engine version, GUI or headless mode, and startup time"),
        t(serverId, "open_jeb_project", "[Android Tools] Open Jeb project. Open or create a JEB project from an artifact file or existing .jdb2 database on the engine host", p("path", "string", "", true)),
        t(serverId, "list_jeb_units", "[Android Tools] List Jeb units. List project units by stable unit path and type, with optional wildcard filter, parent restriction, and pagination", p("filter", "string", "", false), p("parent_unit_path", "string", "", false), p("index", "integer", "", false), p("count", "integer", "", false)),
        t(serverId, "decompile_jeb_item", "[Android Tools] Decompile Jeb item. Decompile one type or method to engine pseudo-code using its decompiler (Dalvik, Java, or native)", p("unit_path", "string", "", false), p("item_address", "string", "", true), p("item_kind", "string", "", true)),
        t(serverId, "inspect_byte_entropy", "[Binary Analysis] Inspect byte entropy. Inspect Shannon byte entropy for one explicit local file offline, without execution, extraction or external tools", p("path", "string", "", true), p("block_size", "integer", "", false), p("offset", "integer", "", false), p("length", "integer", "", false), p("detail", "string", "", false)),
        t(serverId, "inspect_binary_layout", "[Binary Analysis] Inspect binary layout. Inspect an explicit local binary without launching it as a host process", p("path", "string", "Absolute filesystem path to the selected binary", true), p("pwntools_timeout_ms", "integer", "Deadline in milliseconds for each owned pwntools command in this call. Omitted means 30000. Integer from 1 to 2147483647. Applies on the nex", false), p("detail", "string", "complete returns the complete analysis Evidence inline. summary retains that complete Evidence in this session and returns its inspect_analy", false)),
        t(serverId, "inspect_analysis_view", "[Binary Analysis] Inspect analysis view. Use javascript-routes collections to query route patterns, source-linked occurrences, source status, unknowns or summary counts from retained JavaScript application or web-bundle Evidence", p("source", "object", "", true), p("view", "object", "", true)),
        t(serverId, "inspect_pe_resources", "[Binary Analysis] Inspect PE resources. Inspect one explicit local PE32/PE32+ image's complete bounded type/name/language resource tree without executing it or opening a disassembler", p("path", "string", "Absolute filesystem path to a PE image", true), p("max_file_bytes", "integer", "Maximum stable artifact bytes to read (default 64 MiB)", false), p("max_entries", "integer", "Maximum resource directory entries to examine; exceeding the budget fails without partial success", false)),
        t(serverId, "inspect_native_exports", "[Binary Analysis] Inspect native exports", p("path", "string", "Absolute filesystem path to a PE or ELF image", true), p("max_file_bytes", "integer", "Maximum stable artifact bytes to read (default 256 MiB)", false), p("detail", "string", "complete returns the complete analysis Evidence inline. summary retains that complete Evidence in this session and returns its inspect_analy", false)),
        t(serverId, "inspect_devicetree", "[Binary Analysis] Inspect device tree. Inspect an explicit local flattened devicetree blob (FDT v16/v17) without execution or external tools", p("path", "string", "", true), p("max_file_bytes", "integer", "", false), p("max_nodes", "integer", "", false), p("max_properties", "integer", "", false), p("max_depth", "integer", "", false), p("detail", "string", "", false)),
        t(serverId, "inspect_elf_notes", "[Binary Analysis] Inspect ELF notes. Inspect one explicit local ELF32/ELF64 image's PT_NOTE program headers and SHT_NOTE sections without executing it or opening a disassembler", p("path", "string", "", true), p("max_file_bytes", "integer", "", false), p("max_entries", "integer", "", false)),
        t(serverId, "inspect_apple_ips", "[Apple Platforms] Inspect Apple IPS. Inspect one explicit local Apple .ips crash report without executing a target, launching atos, or opening a disassembler", p("path", "string", "", true), p("max_file_bytes", "integer", "", false), p("max_threads", "integer", "", false), p("max_frames", "integer", "", false), p("max_images", "integer", "", false), p("max_registers", "integer", "", false), p("max_integer_text_bytes", "integer", "", false)),
        t(serverId, "inspect_kernel_module", "[Apple Platforms] Inspect kernel module. Inspect one explicit local ELF32/ELF64 ET_REL Linux kernel module without executing it or opening a disassembler", p("path", "string", "", true), p("max_file_bytes", "integer", "", false), p("max_entries", "integer", "", false)),
        t(serverId, "inspect_pe_load_config", "[Apple Platforms] Inspect PE load config. Inspect PE32/PE32+ TLS callbacks and sized load configuration without executing the image", p("path", "string", "Absolute filesystem path to a PE image", true), p("max_file_bytes", "integer", "Maximum stable artifact bytes to read (default 64 MiB)", false), p("max_callbacks", "integer", "Maximum non-null TLS callbacks to examine; exceeding the budget fails without partial success", false)),
        t(serverId, "inspect_pe_certificates", "[Apple Platforms] Inspect PE certificates. Inspect one explicit local PE32/PE32+ image's IMAGE_DIRECTORY_ENTRY_SECURITY table without executing it, verifying signatures or opening a disassembler", p("path", "string", "Absolute filesystem path to a PE image", true), p("max_file_bytes", "integer", "Maximum stable artifact bytes to read (default 64 MiB)", false), p("max_entries", "integer", "Maximum WIN_CERTIFICATE entries to examine; exceeding the budget fails without partial success", false)),
        t(serverId, "inspect_pe_imports", "[Apple Platforms] Inspect PE imports. Inspect one explicit local PE32/PE32+ image's IMAGE_DIRECTORY_ENTRY_IMPORT and delay-load tables without executing it or opening a disassembler", p("path", "string", "Absolute filesystem path to a PE image", true), p("max_file_bytes", "integer", "Maximum stable artifact bytes to read (default 64 MiB)", false), p("max_dlls", "integer", "Maximum import and delay-load descriptors to examine; exceeding the budget fails without partial success", false), p("max_functions", "integer", "Maximum imported functions to examine across every table; exceeding the budget fails without partial success", false)),
        t(serverId, "inspect_go_binary", "[Apple Platforms] Inspect Go binary", p("path", "string", "Absolute filesystem path to the selected executable or library", true), p("architecture", "string", "CPU family of the slice to read from a universal (FAT) Mach-O. Required for universal files and rejected for every other image.", false), p("detail", "string", "complete returns all metadata inline; summary retains complete Evidence and returns build identity and counts for subsequent retained Go vie", false)),
        t(serverId, "inspect_il2cpp_metadata", "[Apple Platforms] Inspect IL2CPP metadata. Inspect local Unity IL2CPP metadata (24/27/29/31/39), optionally linking a caller-selected 64-bit little-endian Mach-O, PE or ELF binary", p("path", "string", "Absolute filesystem path to a Unity IL2CPP global-metadata.dat file", true), p("detail", "string", "summary (default) retains complete Evidence and returns identity and collection counts; complete returns every image, type, method, member, ", false), p("binary_path", "string", "Absolute path to the caller-selected matching 64-bit IL2CPP binary (Mach-O GameAssembly.dylib, PE GameAssembly.dll or ELF libil2cpp.so); sta", false), p("binary_architecture", "string", "Slice to read from a universal Mach-O binary_path; required for universal binaries and rejected otherwise", false), p("generic_instances", "boolean", "Also link generic method instances (genericMethodTable rows) to code addresses with Il2CppDumper-style Type<Args>\$\$Method<Args> names; needs", false)),
        t(serverId, "inspect_rust_binary", "[Apple Platforms] Inspect Rust binary", p("path", "string", "Absolute filesystem path to the selected executable or library", true), p("architecture", "string", "CPU family of the slice to read from a universal (FAT) Mach-O. Required for universal files and rejected for every other image.", false), p("detail", "string", "", false), p("view", "object", "", false)),
        t(serverId, "inspect_python_pyc", "[Apple Platforms] Inspect Python pyc. Inspect one explicit local CPython 3.7–3.13 .pyc file with a supported 16-byte header without executing bytecode, invoking CPython or recovering source", p("path", "string", "", true), p("max_file_bytes", "integer", "", false), p("max_objects", "integer", "", false), p("max_depth", "integer", "", false), p("max_string_bytes", "integer", "", false), p("detail", "string", "", false), p("view", "object", "", false)),
        t(serverId, "inspect_erlang_beam", "[Apple Platforms] Inspect Erlang BEAM. Inspect one explicit local Erlang BEAM IFF container without executing bytecode or installing Erlang", p("path", "string", "Absolute filesystem path to a BEAM file", true), p("max_file_bytes", "integer", "Maximum stable artifact bytes to read (default 64 MiB)", false), p("max_chunks", "integer", "Maximum IFF chunks to examine; exceeding the budget fails without partial success", false), p("max_atoms", "integer", "Maximum atom-table entries to decode; exceeding the budget fails without partial success", false), p("detail", "string", "", false), p("view", "object", "", false)),
        t(serverId, "compare_wasm_interfaces", "[WebAssembly] Compare Wasm interfaces. Compare typed core WASM import/export names, kinds and exact signatures from two authenticated inspect_wasm_artifact Evidence records", p("left", "object", "Exact complete inspect_wasm_artifact Evidence or retained session reference", true), p("right", "object", "Exact complete inspect_wasm_artifact Evidence or retained session reference", true), p("scope", "string", "Explicit interface selection; static equality does not establish runtime compatibility", true)),
        t(serverId, "inspect_wasm_artifact", "[WebAssembly] Inspect Wasm artifact. Validate an explicit local WASM artifact with caller-supplied WABT 1.0.42", p("path", "string", "Absolute selected local WASM artifact path", true), p("glue_paths", "array", "", false), p("candidate_paths", "array", "", false), p("command_timeout_ms", "integer", "Deadline in milliseconds for each WABT version/analysis command (default 30000), not the whole inspection. Allow additional client time for ", false), p("detail", "string", "complete returns the complete analysis Evidence inline. summary retains that complete Evidence in this session and returns its inspect_analy", false)),
        t(serverId, "inspect_apk_signature", "[Android APK] Inspect APK signature. Inspect declared v1/v2/v3/v3.1 APK signers, certificate fingerprints, algorithm IDs, content digests, SDK ranges, rotation lineage and source stamp without executing Android code", p("path", "string", "Selected local APK/ZIP/AAB; static inspection only", true), p("detail", "string", "", false), p("view", "object", "", false)),
        t(serverId, "inspect_evm_interface", "[EVM] Inspect EVM interface. Inspect explicit local flat EVM bytecode without target execution or chain/RPC access", p("path", "string", "Absolute path to caller-selected local bytecode carrier", true), p("encoding", "string", "Raw bytes or UTF-8 hex with optional 0x prefix and outer ASCII whitespace", true), p("timeout_ms", "integer", "Deadline in milliseconds for the owned EVMole worker command (default 30000), not carrier acquisition or final reply reads. An integer from ", false), p("detail", "string", "complete returns the complete analysis Evidence inline. summary retains that complete Evidence in this session and returns its inspect_analy", false)),
        t(serverId, "inspect_sqlite_database", "[SQLite] Inspect SQLite database. Inspect an explicit local SQLite database snapshot and its existing sibling WAL without modifying source files or opening a native analysis provider", p("path", "string", "Absolute path to an offline SQLite database snapshot", true), p("worker_timeout_ms", "integer", "Deadline in milliseconds for worker startup, queries and reply writing (default 30000), not snapshot acquisition or the whole call. Allow cl", false), p("table", "string", "Exact ordinary table name; views and virtual/shadow tables are not executed", false), p("columns", "array", "Exact row columns in caller-selected order; requires table. Omit to sample all visible columns.", false), p("schema_scope", "string", "Default: compact inventory without a table, or detailed selected-table schema with a table. selected_table requires table. Database explicit", false), p("row_limit", "integer", "Selected-table sample size, default 20; subject to byte and worker resource limits", false)),
        t(serverId, "inspect_recorded_crash", "[Crash Analysis] Inspect recorded crash. Inspect a supplied Linux ELF64 amd64 or AArch64 little-endian core without launching its target or attaching to any live PID", p("path", "string", "Absolute filesystem path to a supplied Linux ELF core", true), p("include_debugger_context", "boolean", "Add core-only mapping candidates through caller-supplied GDB/pwndbg", false), p("pwntools_timeout_ms", "integer", "Deadline in milliseconds for each owned pwntools command in this call. Omitted means 30000. Integer from 1 to 2147483647. Applies on the nex", false)),
        t(serverId, "inspect_firmware_regions", "[Firmware] Inspect firmware regions. Inspect signature candidates in a local firmware file with Binwalk 3.1.x (verified with 3.1.0)", p("path", "string", "Absolute local firmware file; never executed. Relative paths are rejected.", true), p("analysis_timeout_ms", "integer", "Analysis wall-clock budget in milliseconds (default 120000), from tool resolution through inspection and its final workspace cleanup, or ext", false)),
        t(serverId, "extract_firmware", "[Firmware] Extract firmware. Extract caller-selected firmware bytes with Unblob 26.6.x (verified with 26.6.4) into an absent output directory", p("path", "string", "Absolute local firmware file; never executed. Relative paths are rejected.", true), p("analysis_timeout_ms", "integer", "Analysis wall-clock budget in milliseconds (default 120000), from tool resolution through inspection and its final workspace cleanup, or ext", false), p("output_directory", "string", "Absolute, absent output directory; parent must exist", true), p("range", "object", "Explicit file-byte interval; omit to extract the whole file", false), p("max_depth", "integer", "Extraction depth; reaching it yields partial analysis", false), p("max_output_bytes", "integer", "Staging file-byte budget, checked during extraction; transient overshoot is possible", false), p("max_output_files", "integer", "Staging entry budget, including directories and links", false), p("publication_timeout_ms", "integer", "Publication and final cleanup budget in milliseconds, started after extraction and inventory; increase for a slow destination", false)),
        t(serverId, "trace_web_module_imports", "[Web Analysis] Trace web module imports. Trace one exported website script's outgoing native ES imports/re-exports, using exact URL and optional selected import-map context", p("manifest_path", "string", "Absolute path to an export_web_scripts manifest", true), p("script_index", "integer", "Zero-based index in manifest.scripts", true), p("importer_url", "string", "Explicit HTTP(S) module URL; useful when captured inline/base context is unknown", false), p("import_map", "object", "", false)),
        t(serverId, "trace_web_source_location", "[Web Analysis] Trace web source location. Trace one generated position in an exported website script through an explicitly paired local source map", p("manifest_path", "string", "Absolute export_web_scripts manifest path", true), p("script_index", "integer", "Zero-based index in manifest.scripts", true), p("source_map", "object", "", true), p("generated_position", "object", "", true), p("decode_timeout_ms", "integer", "Source-map decode deadline in milliseconds (default 20000), for the owned codec process. Omitted means 20000 and applies on the next trace_w", false)),
        t(serverId, "observe_web_execution", "[Web Analysis] Observe web execution. Instrument one selected CDP page for a finite, externally driven precise coverage window", p("cdp_endpoint", "string", "", true), p("allowed_origins", "array", "", false), p("target_id", "string", "", true), p("command_timeout_ms", "integer", "", false), p("observation_ms", "integer", "", false)),
        t(serverId, "inspect_web_event_listeners", "[Web Analysis] Inspect web event listeners. Inspect listeners registered directly on the first main-document CSS match in one selected CDP page", p("cdp_endpoint", "string", "", true), p("allowed_origins", "array", "", false), p("target_id", "string", "", true), p("command_timeout_ms", "integer", "", false), p("selector", "string", "", true)),
        t(serverId, "inspect_web_network_capture", "[Web Analysis] Inspect web network capture. Explicit record_ordinals also decode retained protobuf/gRPC bodies whose declared content type is recognized", p("capture_path", "string", "Absolute local HAR, mitmproxy, Fiddler SAZ or aioquic qlog capture path.", true), p("format", "string", "Declared input artifact format.", true), p("record_ordinals", "array", "Zero-based producer record ordinals, in caller-selected order. Omit to inspect every record.", false), p("protobuf", "object", "", false), p("sensitive_values", "array", "Literal text excluded from capture payloads, byte/numeric projections, producer coordinates, artifact paths/digests and diagnostic text. Dec", false), p("capture_timeout_ms", "integer", "Capture wall-clock deadline in milliseconds (default 30000), from decoder start through process exit. Omitted means 30000 and applies on the", false), p("detail", "string", "complete returns the complete analysis Evidence inline. summary retains that complete Evidence in this session and returns its inspect_analy", false)),
        t(serverId, "recover_javascript_sources", "[Web Analysis] Recover JavaScript sources. Recover readable modules from local UTF-8 JavaScript scripts/bundles into an absent directory, using caller-supplied Wakaru ^1.13.0 (verified with 1.14.0) on Linux x64", p("path", "string", "", true), p("additional_paths", "array", "", false), p("output_directory", "string", "", true), p("extraction_mode", "string", "", false), p("rewrite_level", "string", "", false)),
        t(serverId, "inspect_pcap_client", "[PCAP] Inspect PCAP client. Inspect the caller-selected tshark and capinfos binaries: selected commands, selection sources, and versions"),
        t(serverId, "inspect_pcap_capture", "[PCAP] Inspect PCAP capture. Request detail summary for capture identity/counts, then page retained facts with inspect_analysis_view kind pcap", p("path", "string", "", true), p("detail", "string", "", false)),
        t(serverId, "identify_flutter_build", "[Flutter] Identify Flutter build", p("path", "string", "", true)),
        t(serverId, "inspect_dart_aot", "[Flutter] Inspect Dart AOT. Inspect one ABI's legacy split VM/isolate Dart AOT snapshot inside an APK (complete structural coverage: Dart 2.14 FullAOT kind 3)", p("path", "string", "", true), p("abi", "string", "", false), p("detail", "string", "", false)),
        t(serverId, "inspect_apktool_client", "[APKTool] Inspect APKTool client. Inspect the caller-selected apktool launcher: resolved command, selection source, and version"),
        t(serverId, "decode_android_resources", "[APKTool] Decode Android resources", p("path", "string", "", true), p("include_strings", "boolean", "", false), p("string_tables_only", "boolean", "", false), p("locale", "string", "", false), p("locales", "array", "", false), p("decode_timeout_ms", "integer", "", false)),
        t(serverId, "export_web_scripts", "[Web Export] Export web scripts. Export JavaScript bytes already retained in one local inspect_web_page or capture_browser_scenario JSON capture (normalized result or complete Evidence)", p("capture_path", "string", "", true), p("output_directory", "string", "", true)),
        t(serverId, "list_browser_targets", "[Browser] List browser targets. List page targets exposed by the selected loopback Chrome DevTools Protocol endpoint", p("cdp_endpoint", "string", "", true), p("allowed_origins", "array", "", false)),
        t(serverId, "inspect_web_page", "[Browser] Inspect web page. Passively inspect one selected page target through CDP without evaluating JavaScript, navigating, clicking, closing, or mutating the page", p("cdp_endpoint", "string", "", true), p("allowed_origins", "array", "", false), p("target_id", "string", "", true), p("command_timeout_ms", "integer", "", false), p("observation_ms", "integer", "", false), p("include_accessibility_text", "boolean", "", false), p("include_console_text", "boolean", "", false), p("include_json_body_shapes", "boolean", "", false), p("include_websocket_shapes", "boolean", "", false), p("include_storage_keys", "boolean", "", false), p("include_storage_fingerprints", "boolean", "", false), p("include_script_sources", "boolean", "", false)),
        t(serverId, "analyze_web_bundle", "[Browser] Analyze web bundle", p("cdp_endpoint", "string", "Literal loopback CDP endpoint: http://127.0.0.1:PORT, or ws://127.0.0.1:PORT/devtools/browser/ID.", true), p("allowed_origins", "array", "", false), p("target_id", "string", "Exact CDP target identifier selected from the endpoint's target listing.", true), p("command_timeout_ms", "integer", "CDP WebSocket handshake and per-command response deadline in milliseconds (default 20000). Omitted means 20000 and applies on the next call.", false), p("observation_ms", "integer", "", false), p("include_accessibility_text", "boolean", "Whether to include accessibility text in the result.", false), p("include_console_text", "boolean", "Whether to include console text in the result.", false), p("include_json_body_shapes", "boolean", "Whether to include json body shapes in the result.", false), p("include_websocket_shapes", "boolean", "Whether to include websocket shapes in the result.", false), p("include_storage_keys", "boolean", "Whether to include storage keys in the result.", false), p("include_storage_fingerprints", "boolean", "Whether to include storage fingerprints in the result.", false), p("include_script_sources", "boolean", "Whether to include script sources in the result.", false), p("fetch_source_maps", "boolean", "", false), p("max_heap_mb", "integer", "Heap phase: maximum V8 old-space for the isolated inert-script analyzer, in MiB (default 1024, range 128..16384). Omitted means 1024 and app", false), p("analysis_timeout_ms", "integer", "Per-script deadline phase: maximum startup, analysis and result transfer time for one captured script, in milliseconds (default 30000, range", false), p("total_analysis_timeout_ms", "integer", "Total analysis deadline phase: maximum time for all captured-script analysis, in milliseconds (default 300000, range 1..2147483647). Omitted", false), p("source_map_fetch_timeout_ms", "integer", "Source-map fetch deadline in milliseconds (default 30000). Omitted means 30000 and applies on the next fetch. A fetch overrun is an item lim", false), p("detail", "string", "", false), p("view", "object", "", false)),
        t(serverId, "observe_web_session", "[Browser] Observe web session. Arm a CDP observation window of the requested duration while the user operates the page", p("cdp_endpoint", "string", "", true), p("allowed_origins", "array", "", false), p("target_id", "string", "", true), p("observation_ms", "integer", "", false), p("command_timeout_ms", "integer", "", false)),
        t(serverId, "discover_webmcp_tools", "[Browser] Discover WebMCP tools. Passively inventory every page-registered WebMCP tool and its complete structural input-schema summary using the experimental CDP WebMCP domain", p("cdp_endpoint", "string", "", true), p("allowed_origins", "array", "", false), p("target_id", "string", "", true), p("observation_ms", "integer", "", false), p("command_timeout_ms", "integer", "", false)),
        t(serverId, "compare_web_captures", "[Browser] Compare web captures", p("before", "object", "The earlier or left-hand observation to compare.", false), p("after", "object", "The later or right-hand observation to compare.", false), p("before_scenario", "object", "Earlier complete normalized_result from capture_browser_scenario.", false), p("after_scenario", "object", "Later complete normalized_result from capture_browser_scenario.", false), p("normalization", "object", "Exact-literal rules committed in the comparison result.", false)),
        t(serverId, "capture_web_screenshot", "[Browser] Capture web screenshot. Capture the current visible viewport of one configured page as an inline, content-addressed PNG artifact", p("cdp_endpoint", "string", "", true), p("allowed_origins", "array", "", false), p("target_id", "string", "", true), p("command_timeout_ms", "integer", "", false)),
        t(serverId, "compare_web_screenshots", "[Browser] Compare web screenshots. Compare two self-verifying PNG screenshot artifacts with local pixel metrics", p("before", "object", "", true), p("after", "object", "", true), p("channel_threshold", "integer", "", false)),
        t(serverId, "capture_browser_scenario", "[Browser] Capture browser scenario. Run a controlled browser scenario when passive observation cannot exercise the application", p("browser", "object", "Required launch/connect selection. Launch uses the selected executable and an owned temporary profile; connect uses one loopback CDP endpoin", true), p("start_url", "object", "Required initial HTTP(S) URL. Query values must be declared separately as public literals or secret references.", true), p("environment", "object", "Optional deterministic browser settings. Defaults to 1280x720, en-US, UTC, light mode, reduced motion, and blocked service workers.", false), p("actions", "array", "Required sequence of explicit browser actions.", true), p("storage", "object", "Optional initial cookies, local storage, or session storage. Defaults to empty; every supplied value and origin must be declared and within ", false), p("secrets", "array", "Optional environment-variable references for values used in actions, URLs, storage, or replay, and for redacting matching observed text. Sec", false), p("capture", "object", "Optional retained artifacts and event families. Defaults to only a final sanitized URL; request screenshots, DOM, accessibility, history, st", false), p("command_timeout_ms", "integer", "CDP WebSocket handshake and per-command response deadline in milliseconds (default 20000). Omitted means 20000 and applies on the next call.", false)),
        t(serverId, "inspect_electron_fuses", "[Electron] Inspect Electron fuses. Inspect packaged Electron fuse bytes in one local macOS app bundle, Electron Framework binary, Windows PE executable, or Linux ELF executable", p("path", "string", "Absolute filesystem path to a macOS .app bundle, Electron Framework binary, Windows PE executable, or Linux ELF executable", true)),
        t(serverId, "list_electron_targets", "[Electron] List Electron targets. List every Electron file:// page target from a selected loopback CDP endpoint", p("cdp_endpoint", "string", "", true)),
        t(serverId, "inspect_electron_page", "[Electron] Inspect Electron page. Passively inspect one Electron file page by supplying its loopback CDP endpoint and target ID", p("cdp_endpoint", "string", "", true), p("target_id", "string", "", true), p("observation_ms", "integer", "", false), p("command_timeout_ms", "integer", "", false), p("include_script_sources", "boolean", "", false)),
        t(serverId, "analyze_javascript_application", "[Electron] Analyze JS application. Reconstruct one local ASAR or extracted JavaScript application as an inline application graph without executing it", p("input_path", "string", "Absolute path to the JavaScript application on the machine running REA.", true), p("format", "string", "Declared input artifact format.", false), p("integrity_policy", "string", "", false), p("source_paths", "array", "Exact relevant file paths from the artifact inventory, relative to the application root, analyzed in first-request order before unselected s", false), p("max_semantic_nodes", "integer", "Maximum retained semantic nodes across the application; defaults to 100000. Worker heap and parent result admission remain enforced.", false), p("max_semantic_nodes_per_file", "integer", "Maximum retained semantic nodes per source file; defaults to 20000, additionally bounded by max_semantic_nodes and memory/transfer limits.", false), p("max_semantic_unknowns", "integer", "Maximum retained semantic unknown records across the application; defaults to 100000. Omitted emission occurrences are counted by family and", false), p("max_semantic_unknowns_per_file", "integer", "Maximum retained semantic unknown records per source file; defaults to 20000, additionally bounded by max_semantic_unknowns and memory/trans", false), p("max_heap_mb", "integer", "Maximum V8 old-space heap in MiB for the isolated JavaScript source analyzer; defaults to 1024. This does not change the CLI/MCP process hea", false), p("analysis_timeout_ms", "integer", "Maximum startup, analysis and transfer time per JavaScript source, in milliseconds; defaults to 300000. On timeout, return collected partial", false), p("detail", "string", "", false), p("view", "object", "", false)),
        t(serverId, "reconcile_javascript_runtime", "[Electron] Reconcile JS runtime. Reconcile verified static JavaScript application graphs with existing passive web/Electron CDP, passive V8 Inspector, or provider-owned active Electron Evidence", p("static_layers", "array", "", true), p("runtime_observations", "array", "", true)),
        t(serverId, "capture_electron_scenario", "[Electron] Capture Electron scenario. Use this for a provider-owned Electron run when passive CDP or Inspector observation cannot exercise application behavior", p("executable_path", "string", "Absolute local filesystem path for the Electron executable; relative paths are rejected.", true), p("application_path", "string", "Absolute local filesystem path for the Electron application; relative paths are rejected.", true), p("application_root", "string", "Absolute local filesystem root for application-relative paths; omit to derive it from the application path. Relative paths are rejected.", false), p("args", "array", "", false), p("actions", "array", "", false)),
        t(serverId, "list_javascript_runtime_targets", "[Electron] List JS runtime targets. List every eligible Node/Electron V8 Inspector target from an explicit literal-loopback endpoint", p("inspector_endpoint", "string", "", true)),
        t(serverId, "observe_javascript_runtime", "[Electron] Observe JS runtime. Attach passively to one Node/Electron V8 Inspector target by supplying its loopback Inspector endpoint and target ID", p("inspector_endpoint", "string", "", true), p("target_id", "string", "", true), p("runtime_kind", "string", "", false), p("observation_ms", "integer", "", false)),
        t(serverId, "trace_application_feature", "[Application Analysis] Trace application feature. Trace a typed literal seed through every reachable part of an authenticated JavaScript Application Graph supplied as inline Evidence or exact same-session retained references", p("native_observations", "array", "", false), p("seed", "object", "", true), p("direction", "string", "Direction in which to traverse or compare relationships.", false), p("application", "object", "", true)),
        t(serverId, "trace_javascript_semantics", "[Application Analysis] Trace JS semantics. Trace static JavaScript data-flow, direct call/return, and closure relations from inline application Evidence or an exact same-session retained reference", p("application", "object", "", true), p("query", "object", "Non-empty feature or text query to investigate.", true)),
        t(serverId, "compare_application_versions", "[Application Analysis] Compare app versions. Compare two authenticated JavaScript Application Graph versions supplied as inline Evidence or exact same-session retained references", p("left_native_observations", "array", "", false), p("right_native_observations", "array", "", false), p("left", "object", "Left-hand input used for comparison or differential execution.", true), p("right", "object", "Right-hand input used for comparison or differential execution.", true)),
        t(serverId, "compare_source_to_bundle", "[Application Analysis] Compare source to bundle", p("reference", "object", "", true), p("application", "object", "", true), p("view", "object", "", false)),
        t(serverId, "compare_javascript_export_shapes", "[Application Analysis] Compare JS export shapes. Compare static return shapes for one exact module/export selector on each authenticated JavaScript Application Graph supplied as inline Evidence or exact same-session retained references", p("left_module_path", "string", "Local filesystem path for left module.", true), p("left_export_name", "string", "", true), p("right_module_path", "string", "Local filesystem path for right module.", true), p("right_export_name", "string", "", true), p("left", "object", "Left-hand input used for comparison or differential execution.", true), p("right", "object", "Right-hand input used for comparison or differential execution.", true)),
        t(serverId, "build_reconstruction_obligation_ledger", "[Reconstruction] Build reconstruction ledger. Generate the complete deterministic ReconstructionObligationLedger from an authenticated Evidence bundle, reviewed obligations, and an explicit reconstruction manifest", p("evidence_bundle", "object", "", true), p("reviewed_obligations", "array", "", true), p("manifest", "object", "", true)),
        t(serverId, "evaluate_reconstruction_coverage", "[Reconstruction] Evaluate reconstruction coverage. Evaluate inline evidence-backed reconstruction coverage against one named boundary", p("coverage", "object", "Exact reconstruction-coverage commitment to verify.", true), p("boundary_id", "string", "Exact reconstruction boundary identifier to evaluate.", true)),
        t(serverId, "export_environment_bundle", "[Reconstruction] Export environment bundle", p("source", "object", "The capture the environment is built from: capture_browser_scenario Evidence, or HAR inspect_web_network_capture Evidence.", true), p("output_directory", "string", "Absolute, absent output directory for the bundle folder; parent must exist.", true), p("name", "string", "Env folder and env id (kebab-case). Default: the manifest's name; required when the capture's origin is a loopback or IP address.", false), p("template_rules", "array", "Opt-in template rules, each at most once; [] applies only the format rules. Default: after-uid-literal, opaque-token, sibling-variance and p", false), p("package_tarball", "string", "Absolute path of a packed rea-agents tarball the bundle installs instead of the registry package of this version.", false), p("dry_run", "boolean", "Report what the bundle would hold without writing anything.", false)),
        t(serverId, "project_android_application_graph", "[Reconstruction] Project Android app graph. Project authenticated APK inventory_artifact Evidence into an execution-free Android application inventory", p("inventory_evidence", "array", "", true)),
        t(serverId, "project_apple_application_graph", "[Reconstruction] Project Apple app graph. Project authenticated IPA, macOS .app directory, ZIP, or DMG inventory Evidence into an execution-free Apple application inventory", p("inventory_evidence", "array", "", true)),
        t(serverId, "project_harmony_application_graph", "[Reconstruction] Project Harmony app graph. Project authenticated HarmonyOS HAP, HSP, or App Pack inventory Evidence into an execution-free application inventory", p("inventory_evidence", "array", "", true)),
        t(serverId, "open_bundle_resources", "[Reconstruction] Open bundle resources. Open a local app bundle for read-only plist, Interface Builder, asset catalog, and keyed archive inspection without resolving or inventing its executable identity", p("path", "string", "Absolute local filesystem path for the analysis target; relative paths are rejected.", true)),
        t(serverId, "open_binary", "[Reconstruction] Open binary. Open a local executable, application bundle, archive, JavaScript, source map, plist, or analysis database after validation", p("path", "string", "Absolute local filesystem path for the analysis target; relative paths are rejected.", true), p("format", "string", "Explicit interpretation: dos-com or raw-binary (headerless), or gamecube-dol (GameCube/Wii DOL, never auto-detected). Omission preserves hea", false), p("architecture", "string", "Caller-supplied CPU family. Required with format=raw-binary; rejected otherwise. format=gamecube-dol takes powerpc from the header, so it mu", false), p("entry_point", "string", "Caller-supplied load and entry address (0xhex). File offset 0 is mapped here. Required with format=raw-binary; rejected otherwise. Optional ", false), p("provider_id", "string", "Exact deep-analysis provider ID, or automatic selection when omitted.", false), p("snapshot_path", "string", "Absolute local filesystem path for the analysis snapshot; relative paths are rejected.", false)),
        t(serverId, "close_binary", "[Reconstruction] Close binary", p("snapshot_path", "string", "", false), p("overwrite", "boolean", "", false)),
        t(serverId, "binary_session", "[Reconstruction] Get binary session. Report the active binary target or resource-only app root, provider and capability availability when applicable, client-feature, analysis, and server-identity status without starting analysis.", p("expected_package_version", "string", "", false), p("expected_catalog_digest", "string", "", false), p("expected_server_path", "string", "", false)),
        t(serverId, "export_evidence_bundle", "[Reconstruction] Export evidence bundle. Atomically write the session's deterministic Evidence bundle to the requested local path", p("path", "string", "Absolute local filesystem path for the exported evidence bundle; relative paths are rejected.", true), p("overwrite", "boolean", "Whether an existing destination may be replaced.", false)),
        t(serverId, "import_evidence_bundle", "[Reconstruction] Import evidence bundle. Read the JSON bundle at the supplied local path, validate every Evidence ID and canonical manifest, then atomically merge it", p("path", "string", "Absolute local filesystem path for the evidence bundle to import; relative paths are rejected.", true)),
        t(serverId, "get_evidence_bundle", "[Reconstruction] Get evidence bundle. Return every retained Evidence record and residual unknown as a complete canonical bundle by default", p("detail", "string", "", false), p("filters", "object", "", false)),
        t(serverId, "capture_process_scenario", "[Reconstruction] Capture process scenario. Run one caller-selected command under a PTY and return process capture Evidence with residual unknowns", p("executable", "string", "", true), p("arguments", "array", "", false), p("working_directory", "string", "", false), p("environment", "object", "", false), p("filesystem_observation_paths", "array", "", false), p("terminal", "object", "", false), p("events", "array", "", false), p("timeout_ms", "integer", "", false), p("idle_timeout_ms", "integer", "", false), p("settle_ms", "integer", "", false), p("finalization_ms", "integer", "", false), p("limits", "object", "", false), p("normalization", "object", "", false)),
        t(serverId, "compare_process_captures", "[Reconstruction] Compare process captures. Compare two compatible process capture observations across terminal, interaction, lifecycle, process, filesystem, command-shim, HTTP, and WebSocket evidence", p("left", "object", "Left-hand input used for comparison or differential execution.", true), p("right", "object", "Right-hand input used for comparison or differential execution.", true), p("trace_spec", "object", "", false), p("max_capture_age_ms", "integer", "Maximum permitted capture age ms for this operation.", false)),
        t(serverId, "compare_artifacts", "[Reconstruction] Compare artifacts. Compare complete artifact inventories by logical occurrence path, content identity, metadata, and graph relations", p("left", "object", "Left-hand input used for comparison or differential execution.", true), p("right", "object", "Right-hand input used for comparison or differential execution.", true)),
        t(serverId, "compare_functions", "[Reconstruction] Compare functions. Compare two explicit analyze_function Evidence records or exact retained session references across identity, exact provider text, calls, references, strings, and address-normalized CFG topology", p("left", "object", "Left-hand input used for comparison or differential execution.", true), p("right", "object", "Right-hand input used for comparison or differential execution.", true)),
        t(serverId, "match_native_functions", "[Reconstruction] Match native functions. Match two retained list_procedures inventories by unique exact reported symbols, then unique identical observed function bytes", p("left", "string", "Left list_procedures Evidence or exact retained reference", true), p("right", "string", "Right list_procedures Evidence or exact retained reference", true), p("left_observations", "array", "Optional same-context list_names, procedure_info, read_bytes or analyze_function Evidence", false), p("right_observations", "array", "Optional same-context list_names, procedure_info, read_bytes or analyze_function Evidence", false), p("detail", "string", "", false)),
        t(serverId, "compare_bundles", "[Reconstruction] Compare bundles. Compare two canonical Evidence bundles by exact record membership, explicit one-to-one observation pairs, and complete residual-unknown revision histories", p("left_bundle_path", "string", "Absolute local filesystem path to the left Evidence bundle.", true), p("right_bundle_path", "string", "Absolute local filesystem path to the right Evidence bundle.", true), p("record_pairs", "array", "", false)),
        t(serverId, "find_changed_behavior", "[Reconstruction] Find changed behavior. Aggregate validated process, artifact, and function comparison Evidence", p("comparisons", "array", "Validated comparison Evidence records to aggregate.", true)),
        t(serverId, "build_call_path", "[Reconstruction] Build call path. Build every shortest direct-callee path inline from complete analyze_function Evidence records using exact canonical addresses", p("functions", "array", "", true), p("start", "object", "", true), p("goal", "object", "", true)),
        t(serverId, "correlate_static_and_runtime", "[Reconstruction] Correlate static and runtime. Evaluate every explicit caller-declared hypothesis between exact static comparison findings and runtime comparison dimensions", p("static_comparisons", "array", "", true), p("runtime_comparisons", "array", "", true), p("mappings", "array", "", true)),
        t(serverId, "verify_reconstruction", "[Reconstruction] Verify reconstruction. Verify a finite typed behavioral and structural specification against a canonical Evidence bundle", p("specification", "object", "", true)),
        t(serverId, "list_unknowns", "[Unknowns Tracking] List unknowns. List current residual-unknown heads in deterministic ID order, with optional exact status, severity, and domain filters", p("status", "string", "", false), p("severity", "string", "", false), p("domain", "string", "", false), p("offset", "integer", "", false), p("limit", "integer", "", false)),
        t(serverId, "record_unknown", "[Unknowns Tracking] Record unknown. Create one deterministic residual unknown and immutable mutation evidence", p("question", "string", "", true), p("severity", "string", "", true), p("domain", "string", "", true), p("comparison_conclusion_scope", "object", "", false), p("required_authority", "string", "", true), p("required_confidence", "string", "", true), p("required_environment", "string", "", true), p("recommended_probes", "array", "", true), p("relationships", "array", "", true), p("supporting_evidence_ids", "array", "", false), p("contradicting_evidence_ids", "array", "", false)),
        t(serverId, "update_unknown", "[Unknowns Tracking] Update unknown. Append one immutable full-state revision and mutation evidence", p("unknown_id", "string", "Exact residual-unknown identifier.", true), p("expected_revision", "integer", "Expected revision used to reject stale or mismatched input.", true), p("status", "string", "Current lifecycle or verification status.", true), p("severity", "string", "", true), p("supporting_evidence_ids", "array", "Ordered Evidence identifiers for the supporting observations.", true), p("contradicting_evidence_ids", "array", "Ordered Evidence identifiers for the contradicting observations.", true), p("required_authority", "string", "", true), p("required_confidence", "string", "", true), p("required_environment", "string", "", true), p("recommended_probes", "array", "", true), p("relationships", "array", "", true), p("resolution", "string", "", true)),
        t(serverId, "verify_unknown_resolution", "[Unknowns Tracking] Verify unknown resolution. Revalidate the current residual-unknown head against live bundled evidence, exact authority/confidence/environment requirements, and revision integrity", p("unknown_id", "string", "", true)),
        t(serverId, "get_navigation_context", "[Context] Get navigation context. Return the selected document, current address, and containing/current procedure in one provider-neutral result", p("document", "string", "", false)),
        t(serverId, "inspect_address_context", "[Context] Inspect address context. Inspect one explicit reproducible address for its analyzed name, containing procedure, regular and inline comments, and matching bookmarks", p("address", "string", "", true), p("document", "string", "", false))
    )

    /**
     * Searches the REA MCP tools catalog by keyword, category, or tool name so the AI Agent
     * (or user in the MCP UI) can quickly discover the exact tool and parameter schema needed.
     */
    fun searchTools(
        query: String = "",
        category: String = "",
        maxResults: Int = 35,
        serverId: String = "rea_mcp"
    ): List<McpRemoteTool> {
        val q = query.trim().lowercase(Locale.ROOT)
        val cat = category.trim().lowercase(Locale.ROOT)
        val all = buildDefaultReaTools(serverId)
        if (q.isEmpty() && cat.isEmpty()) return all.take(maxResults)

        val terms = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return all.mapNotNull { tool ->
            val descLower = tool.description.lowercase(Locale.ROOT)
            val nameLower = tool.name.lowercase(Locale.ROOT)
            if (cat.isNotEmpty() && !descLower.contains("[$cat") && !descLower.contains(cat)) {
                return@mapNotNull null
            }
            if (terms.isEmpty()) return@mapNotNull tool to 10
            var score = 0
            for (term in terms) {
                when {
                    nameLower == term -> score += 100
                    nameLower.startsWith(term) -> score += 50
                    nameLower.contains(term) -> score += 30
                    descLower.contains(term) -> score += 15
                    tool.parameters.any { it.name.lowercase(Locale.ROOT).contains(term) || it.description.lowercase(Locale.ROOT).contains(term) } -> score += 8
                }
            }
            if (score > 0) tool to score else null
        }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(maxResults)
    }

    fun listCategories(): List<Pair<String, Int>> {
        val counts = linkedMapOf<String, Int>()
        for (tool in buildDefaultReaTools()) {
            val cat = if (tool.description.startsWith("[")) {
                tool.description.substringAfter("[").substringBefore("]").ifBlank { "General" }
            } else {
                "General"
            }
            counts[cat] = (counts[cat] ?: 0) + 1
        }
        return counts.entries.map { it.key to it.value }
    }

    fun buildToolsJsonArray(serverId: String = "rea_mcp"): JsonArray {
        val arr = JsonArray()
        for (tool in buildDefaultReaTools(serverId)) {
            val props = JsonObject()
            val reqArr = JsonArray()
            for (param in tool.parameters) {
                props.add(param.name, JsonObject().apply {
                    addProperty("type", param.type.ifBlank { "string" })
                    if (param.description.isNotBlank()) {
                        addProperty("description", param.description)
                    }
                })
                if (param.required) {
                    reqArr.add(param.name)
                }
            }
            arr.add(JsonObject().apply {
                addProperty("name", tool.name)
                addProperty("description", tool.description)
                add("inputSchema", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", props)
                    if (reqArr.size() > 0) {
                        add("required", reqArr)
                    }
                })
            })
        }
        return arr
    }

    /**
     * Generates the hybrid REA MCP stdio server script (`rea_mcp_server.py`).
     *
     * - If `rea-agents` is installed inside Debian (`/usr/local/bin/rea-agents`), it proxies requests
     *   directly to `rea-agents mcp` for full native REA execution.
     * - Otherwise, it serves all 205 REA tools from `rea_mcp_tools.json` and executes built-in
     *   reverse-engineering handlers (APK/DEX/Manifest/Layout XML/ELF/Strings/Byte Search/Objdump/Rizin/SQLite).
     */
    fun buildReaServerPythonScript(toolsJsonPath: String): String = """
#!/usr/bin/env python3
import sys, json, os, zipfile, struct, re, hashlib, sqlite3, subprocess, shutil

TOOLS_JSON_PATH = "${toolsJsonPath.replace("\\", "/")}"

def load_tools():
    if os.path.isfile(TOOLS_JSON_PATH):
        try:
            with open(TOOLS_JSON_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return []

def resolve_target_path(args):
    for k in ("path", "apk_path", "binary_path", "file", "artifact_path", "db_path", "package_path", "target"):
        v = args.get(k)
        if isinstance(v, str) and v.strip():
            return v.strip()
    return ""

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(65536)
            if not b:
                break
            h.update(b)
    return h.hexdigest()

def extract_printable_strings(path, min_len=4, limit=200, pattern=None):
    results = []
    rx = re.compile(pattern, re.IGNORECASE) if pattern else None
    with open(path, "rb") as f:
        data = f.read(8 * 1024 * 1024)
    for m in re.finditer(rb"[\x20-\x7e](' + str(min_len).encode() + rb',)", data):
        s = m.group(0).decode("ascii", errors="ignore")
        if rx and not rx.search(s):
            continue
        results.append({"offset": hex(m.start()), "string": s})
        if len(results) >= limit:
            break
    return results

def handle_builtin_rea_call(name, args):
    target = resolve_target_path(args)
    if name in ("inspect_android_package", "rea_inspect_apk", "project_android_application_graph", "open_bundle_resources"):
        if not target or not os.path.isfile(target):
            return json.dumps({"error": f"File not found: {target}"}, indent=2)
        with zipfile.ZipFile(target, "r") as z:
            names = z.namelist()
            layouts = [n for n in names if n.startswith("res/layout") and n.endswith(".xml")]
            drawables = [n for n in names if (n.startswith("res/drawable") or n.startswith("res/mipmap")) and not n.endswith("/")]
            assets = [n for n in names if n.startswith("assets/") and not n.endswith("/")]
            dexes = [n for n in names if re.match(r"classes\d*\.dex$", n)]
            libs = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
            return json.dumps({
                "tool": name,
                "path": target,
                "sha256": sha256_file(target),
                "size_bytes": os.path.getsize(target),
                "total_entries": len(names),
                "dex_files": dexes,
                "layouts_count": len(layouts),
                "layouts": layouts[:80],
                "drawables_count": len(drawables),
                "drawables_sample": drawables[:60],
                "assets_count": len(assets),
                "assets": assets[:60],
                "native_libs": libs[:50]
            }, indent=2)

    if name in ("extract_artifact", "rea_extract_apk_workspace", "decode_android_resources"):
        if not target or not os.path.isfile(target):
            return json.dumps({"error": f"File not found: {target}"}, indent=2)
        out_dir = args.get("output_dir") or args.get("destination") or "/tmp/rea_extracted"
        os.makedirs(out_dir, exist_ok=True)
        if zipfile.is_zipfile(target):
            with zipfile.ZipFile(target, "r") as z:
                z.extractall(out_dir)
            return json.dumps({"tool": name, "source": target, "output_dir": out_dir, "status": "extracted"}, indent=2)
        return json.dumps({"tool": name, "source": target, "status": "not_a_zip_archive"}, indent=2)

    if name in ("list_strings", "search_strings"):
        if not target or not os.path.isfile(target):
            return json.dumps({"error": f"Target file required and must exist: {target}"}, indent=2)
        q = args.get("query") or args.get("pattern") or args.get("filter")
        limit = int(args.get("limit") or args.get("max_entries") or 150)
        strs = extract_printable_strings(target, min_len=4, limit=limit, pattern=q)
        return json.dumps({"tool": name, "path": target, "count": len(strs), "strings": strs}, indent=2)

    if name == "inspect_sqlite_database":
        if not target or not os.path.isfile(target):
            return json.dumps({"error": f"SQLite file not found: {target}"}, indent=2)
        conn = sqlite3.connect(target)
        cur = conn.cursor()
        tables = [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()]
        schema = {}
        for tb in tables:
            cols = cur.execute(f"PRAGMA table_info('{tb}')").fetchall()
            schema[tb] = [{"cid": c[0], "name": c[1], "type": c[2], "notnull": c[3], "pk": c[5]} for c in cols]
        conn.close()
        return json.dumps({"tool": name, "path": target, "tables": tables, "schema": schema}, indent=2)

    if name == "inspect_with_objdump" and target and os.path.isfile(target):
        cmd = ["objdump", "-x", "-T", target]
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        return (p.stdout or "") + (("\n" + p.stderr) if p.stderr else "")

    if name == "execute_rizin_command":
        rcmd = args.get("command") or "i"
        if target and os.path.isfile(target) and shutil.which("rizin"):
            p = subprocess.run(["rizin", "-q", "-c", rcmd, target], capture_output=True, text=True, timeout=30)
            return (p.stdout or "") + (("\n" + p.stderr) if p.stderr else "")

    if target and os.path.isfile(target):
        return json.dumps({
            "tool": name,
            "path": target,
            "size_bytes": os.path.getsize(target),
            "sha256": sha256_file(target),
            "arguments": args
        }, indent=2)

    return json.dumps({"tool": name, "status": "ok", "arguments": args}, indent=2)

def main():
    tools = load_tools()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:
            continue
        req_id = req.get("id")
        method = req.get("method", "")
        if req_id is None:
            continue
        if method == "initialize":
            res = {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "rea-mcp-server", "version": "2.0.0"}
            }
            print(json.dumps({"jsonrpc": "2.0", "id": req_id, "result": res}), flush=True)
        elif method == "tools/list":
            print(json.dumps({"jsonrpc": "2.0", "id": req_id, "result": {"tools": tools}}), flush=True)
        elif method == "tools/call":
            params = req.get("params", {})
            tname = params.get("name", "")
            targs = params.get("arguments", {}) or {}
            try:
                text = handle_builtin_rea_call(tname, targs)
                print(json.dumps({"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": text}], "isError": False}}), flush=True)
            except Exception as e:
                print(json.dumps({"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": str(e)}], "isError": True}}), flush=True)
        else:
            print(json.dumps({"jsonrpc": "2.0", "id": req_id, "result": {}}), flush=True)

if __name__ == "__main__":
    main()
""".trimIndent() + "\n"

    /**
     * Fast-path execution on Android JVM for Android APK/DEX/XML/Signature/Artifact tools when
     * invoked directly or as a fallback when Debian `rea-agents` is not required.
     */
    fun executeAndroidHostFastPath(toolName: String, arguments: JsonObject): String? {
        return try {
            val rawPath = sequenceOf("apk_path", "path", "package_path", "binary_path", "file", "target")
                .mapNotNull { arguments.get(it)?.takeIf { el -> el.isJsonPrimitive }?.asString }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val scId = ProjectGateway.resolveDefaultScId() ?: "601"
            val apkFile = ApkProjectReconstructor.resolveApkFile(scId, rawPath)

            when (toolName) {
                "rea_decode_xml", "decode_android_resources" -> {
                    val entry = arguments.get("xml_entry")?.asString?.ifBlank { "AndroidManifest.xml" } ?: "AndroidManifest.xml"
                    if (apkFile.isFile) {
                        val bytes = ZipFile(apkFile).use { z ->
                            val ze = z.getEntry(entry) ?: z.entries().asSequence().firstOrNull { it.name.endsWith(entry) }
                            ze?.let { z.getInputStream(it).readBytes() }
                        }
                        if (bytes != null) {
                            val xml = ApkProjectReconstructor.decodeBinaryXmlPureKotlin(bytes)
                            if (xml.isNotBlank()) return "=== MCP [rea_mcp :: $toolName] OK ===\n$xml"
                        }
                    }
                    null
                }

                "rea_analyze_dex", "search_android_classes", "inspect_android_class", "inspect_android_method", "trace_android_references" -> {
                    if (apkFile.isFile) {
                        val query = sequenceOf("query", "class_name", "method_name", "pattern", "symbol", "name")
                            .mapNotNull { arguments.get(it)?.takeIf { el -> el.isJsonPrimitive }?.asString }
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                        val rep = ApkProjectReconstructor.analyzeApkDex(apkFile, "", query, 100)
                        val sb = StringBuilder()
                        sb.appendLine("=== MCP [rea_mcp :: $toolName] OK ===")
                        sb.appendLine("Target APK: ${apkFile.absolutePath}")
                        sb.appendLine("DEX Files: ${rep.dexFilesCount} | Classes: ${rep.totalClassesCount} | Methods: ${rep.totalMethodsCount} | Fields: ${rep.totalFieldsCount}")
                        for (c in rep.appClasses) {
                            sb.appendLine("• ${c.className} (extends ${c.superClass}) -> methods=${c.methods.take(12)} fields=${c.fields.take(10)}")
                        }
                        if (rep.discoveredUrls.isNotEmpty()) sb.appendLine("URLs: ${rep.discoveredUrls}")
                        if (rep.interestingStrings.isNotEmpty()) sb.appendLine("Strings: ${rep.interestingStrings}")
                        return sb.toString().trim()
                    }
                    null
                }

                "rea_inspect_apk", "inspect_android_package", "project_android_application_graph", "inspect_apk_signature", "binary_overview", "inspect_artifact" -> {
                    if (apkFile.isFile) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        apkFile.inputStream().use { input ->
                            val buf = ByteArray(65536)
                            while (true) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                digest.update(buf, 0, r)
                            }
                        }
                        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                        ZipFile(apkFile).use { z ->
                            val names = z.entries().asSequence().map { it.name }.toList()
                            val layouts = names.filter { it.startsWith("res/layout") && it.endsWith(".xml") }
                            val drawables = names.filter { (it.startsWith("res/drawable") || it.startsWith("res/mipmap")) && !it.endsWith("/") }
                            val assets = names.filter { it.startsWith("assets/") && !it.endsWith("/") }
                            val dexes = names.filter { it.matches(Regex("classes\\d*\\.dex")) }
                            val libs = names.filter { it.startsWith("lib/") && it.endsWith(".so") }
                            val certs = names.filter { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC") || it.endsWith(".SF")) }
                            return buildString {
                                appendLine("=== MCP [rea_mcp :: $toolName] OK ===")
                                appendLine("APK Path: ${apkFile.absolutePath}")
                                appendLine("Size: ${apkFile.length()} bytes | SHA-256: $sha256")
                                appendLine("Total Entries: ${names.size} | DEX: $dexes | Certs: $certs")
                                appendLine("Layouts (${layouts.size}): ${layouts.take(60)}")
                                appendLine("Drawables (${drawables.size}): ${drawables.take(40)}")
                                appendLine("Assets (${assets.size}): ${assets.take(40)}")
                                appendLine("Native Libs (${libs.size}): ${libs.take(30)}")
                            }.trim()
                        }
                    }
                    null
                }

                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
