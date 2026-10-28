package pro.sketchware.ai.tools

import android.content.Context
import android.os.Environment
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import pro.sketchware.ai.agent.AgentMemoryStore
import pro.sketchware.ai.agent.AgentPlan
import pro.sketchware.ai.agent.AgentTaskItem
import pro.sketchware.ai.agent.TaskStatus
import pro.sketchware.ai.config.AgentAutonomyMode
import pro.sketchware.ai.extensions.McpClientHub
import pro.sketchware.ai.extensions.McpConnectorConfig
import pro.sketchware.ai.extensions.PluginManager
import pro.sketchware.ai.extensions.SkillManager
import pro.sketchware.ai.ir.BlockCatalog
import pro.sketchware.ai.observe.HeadlessProjectBuilder
import pro.sketchware.ai.observe.RuntimeLogHarness
import pro.sketchware.ai.project.ProjectGateway
import pro.sketchware.ai.provider.ToolDefinition
import pro.sketchware.ai.provider.ToolParameterProperty
import pro.sketchware.ai.shell.DangerousCommandGuard
import pro.sketchware.ai.shell.LinuxDownloader
import pro.sketchware.ai.shell.LinuxSubsystem
import pro.sketchware.ai.shell.OutputSpiller
import pro.sketchware.ai.shell.PromptDetector
import pro.sketchware.ai.shell.ShellResult
import pro.sketchware.ai.shell.ShellRunner
import pro.sketchware.ai.shell.TerminalSessionManager
import java.io.File
import java.util.Locale

object SketchwareToolRegistry {

    private val allTools: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "get_project_summary",
            description = "Inspect the entire Sketchware project: metadata, all Activities/Screens, Custom Views, enabled Libraries, Permissions, Custom Java files, and counts of views/variables/lists/components/events.",
            parameters = emptyList(),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "get_screen_state",
            description = "Get complete details of a specific Screen/Activity or CustomView: all View IDs & types, Variables (boolean/number/String/Map), Lists (ListInt/ListString/ListMap), Components, MoreBlocks, and Events.",
            parameters = listOf(
                ToolParameterProperty("screen_name", "string", "Screen name, e.g. 'main', 'MainActivity', 'home', or custom view name.", required = true)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "read_screen_xml",
            description = "Read the standard Android XML layout of a Screen or CustomView generated from Sketchware's ViewBeans.",
            parameters = listOf(
                ToolParameterProperty("screen_name", "string", "Screen or CustomView name, e.g. 'main' or 'item_row'.", required = true)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "write_screen_xml",
            description = "Design or update a Screen or CustomView layout by providing standard Android XML. Sketchware's ViewBeanParser converts it into genuine visual ViewBeans and auto-registers Button onClick events. Always wrap your layout in a root <LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:orientation=\"vertical\"> ... </LinearLayout>; child views inside the root tag become top-level views on the Sketchware canvas. Supported tags: LinearLayout, RelativeLayout, ScrollView, HorizontalScrollView, TextView, Button, EditText, ImageView, ListView, Spinner, CheckBox, Switch, SeekBar, ProgressBar, WebView, CalendarView.",
            parameters = listOf(
                ToolParameterProperty("screen_name", "string", "Target Screen or CustomView name (e.g. 'main' or 'second').", required = true),
                ToolParameterProperty("xml_content", "string", "Complete Android XML layout string.", required = true)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "create_screen",
            description = "Create a new Activity Screen or CustomView in the Sketchware project.",
            parameters = listOf(
                ToolParameterProperty("screen_name", "string", "Lowercase snake_case name without extension, e.g. 'detail', 'login', 'item_note'.", required = true),
                ToolParameterProperty("is_custom_view", "boolean", "True to create a CustomView (for ListView/RecyclerView row or dialog), false to create an Activity Screen.", required = false),
                ToolParameterProperty("has_toolbar", "boolean", "Whether the Activity has a Toolbar/ActionBar (default true).", required = false),
                ToolParameterProperty("is_fullscreen", "boolean", "Whether the Activity hides StatusBar (default false).", required = false),
                ToolParameterProperty("has_fab", "boolean", "Whether the Activity includes a FloatingActionButton (default false).", required = false),
                ToolParameterProperty("has_drawer", "boolean", "Whether the Activity includes a Navigation Drawer (default false).", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "manage_variable",
            description = "Add or remove a Variable on an Activity screen.",
            parameters = listOf(
                ToolParameterProperty("screen_name", "string", "Activity screen name, e.g. 'main'.", required = true),
                ToolParameterProperty("action", "string", "'add' or 'remove'", required = true, enumValues = listOf("add", "remove")),
                ToolParameterProperty("var_name", "string", "Variable name, e.g. 'score', 'userName', 'dataMap'.", required = true),
                ToolParameterProperty("var_type", "string", "Variable type: 'boolean' (0), 'number' (1), 'string' (2), or 'map' (3).", required = true, enumValues = listOf("boolean", "number", "string", "map"))
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "manage_list",
            description = "Add or remove a List (ArrayList) on an Activity screen.",
            parameters = listOf(
                ToolParameterProperty("screen_name", "string", "Activity screen name, e.g. 'main'.", required = true),
                ToolParameterProperty("action", "string", "'add' or 'remove'", required = true, enumValues = listOf("add", "remove")),
                ToolParameterProperty("list_name", "string", "List name, e.g. 'itemsList', 'notesMapList'.", required = true),
                ToolParameterProperty("list_type", "string", "List type: 'number' (ListInt=1), 'string' (ListString=2), or 'map' (ListMap=3).", required = true, enumValues = listOf("number", "string", "map"))
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "add_component",
            description = "Add a Sketchware Component to an Activity (e.g. Intent, SharedPreferences/File, Calendar, Vibrator, Timer, Dialog, RequestNetwork, ObjectAnimator, MediaPlayer, SoundPool, TextToSpeech, SpeechToText, FirebaseDB, FirebaseAuth).",
            parameters = listOf(
                ToolParameterProperty("screen_name", "string", "Activity screen name, e.g. 'main'.", required = true),
                ToolParameterProperty("component_type", "string", "Component type name: Intent, SharedPreferences, Calendar, Vibrator, Timer, Dialog, RequestNetwork, ObjectAnimator, MediaPlayer, SoundPool, TextToSpeech, SpeechToText, FirebaseDB, FirebaseAuth.", required = true),
                ToolParameterProperty("component_id", "string", "Variable identifier for this component instance, e.g. 'intentNav', 'spPrefs', 'dlgConfirm', 'reqApi', 'timerLoop'.", required = true),
                ToolParameterProperty("param1", "string", "Extra config param1 (for SharedPreferences: file name e.g. 'app_prefs'; for FirebaseDB: database path).", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "create_more_block",
            description = "Create a MoreBlock (reusable custom method) in an Activity. After creating it, populate its blocks using write_event_logic with target_id=<func_name> and event_name='moreBlock'.",
            parameters = listOf(
                ToolParameterProperty("screen_name", "string", "Activity screen name, e.g. 'main'.", required = true),
                ToolParameterProperty("func_name", "string", "Method name in camelCase, e.g. 'refreshNotesList' or 'calculateScore'.", required = true),
                ToolParameterProperty("spec", "string", "Optional parameter specification, e.g. 'calculateScore %d.points %s.playerName'.", required = false),
                ToolParameterProperty("return_type", "string", "Return type: 'void' (default), 'String', 'double', or 'boolean'.", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "search_blocks_catalog",
            description = "Search Sketchware's built-in and extra logic blocks catalog by keyword to find exact opCodes, parameter placeholders, and types.",
            parameters = listOf(
                ToolParameterProperty("query", "string", "Search keyword (e.g. 'toast', 'list', 'intent', 'file', 'text', 'visibility', 'map'). Leave empty to list core blocks.", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "read_event_logic",
            description = "Read the existing BlockBean logic tree (in Block-Script format) and generated Java preview for any Event or MoreBlock in an Activity.",
            parameters = listOf(
                ToolParameterProperty("screen_name", "string", "Activity screen name, e.g. 'main'.", required = true),
                ToolParameterProperty("target_id", "string", "Target ID: 'onCreate' for onCreate_initializeLogic, view ID (e.g. 'btn_add') for view events, component ID (e.g. 'reqApi') for component events, or MoreBlock function name.", required = true),
                ToolParameterProperty("event_name", "string", "Event name: 'initializeLogic' (for onCreate), 'onClick', 'onLongClick', 'onTextChanged', 'onItemClicked', 'onBindCustomView', 'onResponse', 'onErrorResponse', 'onBackPressed', or 'moreBlock'.", required = true)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "write_event_logic",
            description = """Compile and write genuine Sketchware BlockBeans into an Activity's Event or MoreBlock (auto-creates the EventBean if needed, auto-declares missing variables/lists, and auto-injects common Java imports).
You can pass `blocks_input` EITHER as a JSON array of block objects OR as Block-Script DSL:
1) Block-Script DSL example:
setText("tv_title", "Selamat Datang!")
if ([stringEquals([getText("et_input")], "")]) {
  doToast("Input tidak boleh kosong!")
} else {
  addListStr([getText("et_input")], "itemsList")
  listSetData("lv_items", "itemsList")
  listRefresh("lv_items")
  setText("et_input", "")
}
2) For complex custom Java inside visual blocks, use `addSourceDirectly("...java code...")` or `raw_java("...java code...")`.
3) To read a variable inside an expression, use `[getVar:s("myStrVar")]`, `[getVar:d("myNumVar")]`, or `[getVar:b("myBoolVar")]`.""",
            parameters = listOf(
                ToolParameterProperty("screen_name", "string", "Activity screen name, e.g. 'main'.", required = true),
                ToolParameterProperty("target_id", "string", "Target ID: 'onCreate' (for Activity onCreate), view ID (e.g. 'btn_save'), component ID, Activity lifecycle name ('onBackPressed', 'onResume'), or MoreBlock name.", required = true),
                ToolParameterProperty("event_name", "string", "Event name: 'initializeLogic' (for onCreate), 'onClick', 'onItemClicked', 'onBindCustomView', 'onBackPressed', 'moreBlock', etc.", required = true),
                ToolParameterProperty("blocks_input", "string", "Block-Script DSL or JSON array of blocks to compile into Sketchware BlockBeans.", required = true),
                ToolParameterProperty("append", "boolean", "If true, appends to the end of existing blocks in this event; if false (default), replaces the event's blocks.", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "configure_library",
            description = "Enable or disable built-in Sketchware libraries: 'appcompat' (AppCompat & Material Design), 'firebase', 'admob', or 'googlemap'.",
            parameters = listOf(
                ToolParameterProperty("library_name", "string", "Library name: 'appcompat', 'firebase', 'admob', or 'googlemap'.", required = true, enumValues = listOf("appcompat", "firebase", "admob", "googlemap")),
                ToolParameterProperty("enabled", "boolean", "True to enable, false to disable.", required = true)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "add_permissions",
            description = "Add Android Manifest permissions to the project (e.g. 'INTERNET', 'VIBRATE', 'CAMERA', 'READ_EXTERNAL_STORAGE').",
            parameters = listOf(
                ToolParameterProperty("permissions", "string", "Comma-separated permission names, e.g. 'INTERNET, ACCESS_NETWORK_STATE, VIBRATE'.", required = true)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "add_custom_imports",
            description = "Add custom Java import statements to the project's Activities (e.g. 'java.util.Collections, org.json.JSONObject').",
            parameters = listOf(
                ToolParameterProperty("imports", "string", "Comma-separated fully qualified class names to import.", required = true)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "write_custom_java_file",
            description = "Create or update a standalone custom Java/Kotlin class in `.sketchware/data/<sc_id>/files/java/`.",
            parameters = listOf(
                ToolParameterProperty("file_name", "string", "File name with extension, e.g. 'DataHelper.java'.", required = true),
                ToolParameterProperty("source_code", "string", "Complete Java or Kotlin source code including package declaration.", required = true)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "manage_sketchware_files",
            description = "Full filesystem access for Sketchware & project files (list, read, write, append, mkdir, delete, copy, move). Does NOT force '.java' extension — can read/write/delete any XML, JSON, Java, Kotlin, resource, local_library, or system file. Supports path shortcuts: '@project/' (.sketchware/data/<sc_id>/), '@files/' (.sketchware/data/<sc_id>/files/), '@mysc/' (.sketchware/mysc/<sc_id>/), '@libs/' (.sketchware/libs/local_libs/), '@system/' (.sketchware/system/), or any absolute path '/storage/emulated/0/...'.",
            parameters = listOf(
                ToolParameterProperty("action", "string", "Action: 'list', 'read', 'write', 'append', 'mkdir', 'delete', 'copy', or 'move'.", required = true, enumValues = listOf("list", "read", "write", "append", "mkdir", "delete", "copy", "move")),
                ToolParameterProperty("path", "string", "Target file or directory path (e.g. '@libs/SquareImageView_V_1.0.1/res/values/attrs.xml', '@files/resource/values/colors.xml', '@project/local_library', or '/storage/emulated/0/.sketchware/...').", required = true),
                ToolParameterProperty("content", "string", "File content to write or append (required for 'write' and 'append').", required = false),
                ToolParameterProperty("target_path", "string", "Destination path (required for 'copy' and 'move').", required = false),
                ToolParameterProperty("recursive", "boolean", "If true during 'list', lists subdirectories recursively (default false).", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "manage_local_libraries",
            description = """Inspect, install from Maven, import JAR/AAR, enable, disable, or repair local libraries in `.sketchware/data/<sc_id>/local_library` and `.sketchware/libs/local_libs/`.
Actions:
- 'list': inspect all enabled & installed local libraries and see if any JAR/DEX/RES files are missing.
- 'install_maven': download any Maven/JitPack artifact ('maven_coordinate' = 'groupId:artifactId:version', e.g. 'com.google.code.gson:gson:2.10.1' or 'com.airbnb.android:lottie:6.1.0'), extract AAR, compile classes.jar -> classes.dex via D8, and automatically enable it in the project!
- 'import_jar': import any .jar or .aar file from disk ('source_path'), compile classes.dex via D8, and enable it in the project.
- 'enable' / 'disable': enable or disable libraries by 'library_names' (use 'all_missing' with 'disable' to remove broken refs).
- 'repair': auto-create missing res/ folders and remove illegal .java/.kt/0-byte XML files inside res/.""",
            parameters = listOf(
                ToolParameterProperty("action", "string", "Action: 'list', 'install_maven', 'import_jar', 'enable', 'disable', or 'repair'.", required = true, enumValues = listOf("list", "install_maven", "import_jar", "enable", "disable", "repair")),
                ToolParameterProperty("library_names", "string", "Comma-separated local library names, or 'all_missing' when action='disable'.", required = false),
                ToolParameterProperty("maven_coordinate", "string", "Maven coordinate 'groupId:artifactId:version' for action='install_maven'.", required = false),
                ToolParameterProperty("skip_subdependencies", "boolean", "For action='install_maven': if true (default), downloads only the primary artifact; set false to also resolve transitive dependencies.", required = false),
                ToolParameterProperty("source_path", "string", "Path to .jar or .aar file on disk for action='import_jar'.", required = false),
                ToolParameterProperty("package_name", "string", "Optional R-class package name for action='import_jar'.", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "manage_project_resources",
            description = """Manage Sketchware project resources: images/drawables (including creating VectorDrawable XML icons directly!), sounds, fonts, custom Android res/ XML files (colors, styles, drawables, anim, xml), and assets.
Actions:
- 'list': list all registered project images, sounds, fonts, assets, and custom res/ XML files.
- 'add_vector_drawable': create an Android <vector> XML icon ('resource_name' e.g. 'ic_send_white', 'content' = XML) directly in the project's images directory and register its ProjectResourceBean so it can be used immediately in ImageView or backgroundResource!
- 'import': import an image (.png/.9.png/.xml), sound (.mp3/.ogg/.wav), font (.ttf/.otf), or asset from 'source_path' into the project and register it.
- 'write_res_xml': write any custom Android resource XML under res/ ('resource_name' = 'drawable/bg_card.xml', 'values/colors.xml', 'values/styles.xml', 'anim/fade_in.xml', etc.).
- 'write_asset': write text/JSON/HTML/JS content directly to `assets/<resource_name>`.
- 'delete': remove a resource by 'resource_type' and 'resource_name'.""",
            parameters = listOf(
                ToolParameterProperty("action", "string", "Action: 'list', 'add_vector_drawable', 'import', 'write_res_xml', 'write_asset', or 'delete'.", required = true, enumValues = listOf("list", "add_vector_drawable", "import", "write_res_xml", "write_asset", "delete")),
                ToolParameterProperty("resource_type", "string", "Resource category: 'image', 'sound', 'font', 'asset', or 'res_xml'. Default 'image'.", required = false, enumValues = listOf("image", "sound", "font", "asset", "res_xml")),
                ToolParameterProperty("resource_name", "string", "Resource name (e.g. 'ic_logo' for image/vector, or 'drawable/bg_card.xml' for write_res_xml, or 'config.json' for write_asset).", required = false),
                ToolParameterProperty("content", "string", "XML or text content for 'add_vector_drawable', 'write_res_xml', or 'write_asset'.", required = false),
                ToolParameterProperty("source_path", "string", "Source file path on disk for action='import'.", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "configure_project_metadata",
            description = """Read or update project metadata (app name, package name, version code/name, theme colors, minSdk/targetSdk, custom app icon) or delete obsolete screens/events.
Actions:
- 'read': inspect current app name, package name, version, theme colors, and SDK versions.
- 'update': update any combination of app_name, package_name, workspace_name, version_code, version_name, color_primary, color_primary_dark, color_accent, min_sdk, target_sdk, java_version, or icon_source_path.
- 'delete_screen': remove an obsolete Activity or CustomView ('screen_to_delete').
- 'delete_event': remove an event and its blocks ('event_screen', 'event_target_id', 'event_name_to_delete').""",
            parameters = listOf(
                ToolParameterProperty("action", "string", "Action: 'read', 'update', 'delete_screen', or 'delete_event'.", required = true, enumValues = listOf("read", "update", "delete_screen", "delete_event")),
                ToolParameterProperty("app_name", "string", "New application display name.", required = false),
                ToolParameterProperty("package_name", "string", "New application package name (e.g. 'com.example.myapp').", required = false),
                ToolParameterProperty("workspace_name", "string", "New workspace/project name.", required = false),
                ToolParameterProperty("version_code", "string", "New integer version code (e.g. '2').", required = false),
                ToolParameterProperty("version_name", "string", "New version name (e.g. '1.1.0').", required = false),
                ToolParameterProperty("color_primary", "string", "Hex color for colorPrimary (e.g. '#FF6200EE').", required = false),
                ToolParameterProperty("color_primary_dark", "string", "Hex color for colorPrimaryDark (e.g. '#FF3700B3').", required = false),
                ToolParameterProperty("color_accent", "string", "Hex color for colorAccent (e.g. '#FF03DAC5').", required = false),
                ToolParameterProperty("min_sdk", "string", "Minimum SDK version (e.g. '21', '24').", required = false),
                ToolParameterProperty("target_sdk", "string", "Target SDK version (e.g. '28', '33', '34').", required = false),
                ToolParameterProperty("java_version", "string", "Java version ('1.7' or '1.8').", required = false),
                ToolParameterProperty("icon_source_path", "string", "Path to PNG file on disk to set as the custom app launcher icon.", required = false),
                ToolParameterProperty("screen_to_delete", "string", "Screen or CustomView name to delete when action='delete_screen'.", required = false),
                ToolParameterProperty("event_screen", "string", "Screen name for action='delete_event'.", required = false),
                ToolParameterProperty("event_target_id", "string", "Target ID for action='delete_event' (e.g. 'button1').", required = false),
                ToolParameterProperty("event_name_to_delete", "string", "Event name for action='delete_event' (e.g. 'onClick').", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "search_project_code",
            description = "Deep project-wide search across all screens' XML views, all events & MoreBlocks logic blocks (opCode, spec, parameters), all variables/lists/components, and all custom Java/Kotlin/XML/Asset files.",
            parameters = listOf(
                ToolParameterProperty("query", "string", "Text or identifier to search for across the entire project.", required = true),
                ToolParameterProperty("case_sensitive", "boolean", "Whether the search is case-sensitive (default false).", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "manage_checkpoints",
            description = """Manage project checkpoints (snapshots of file, view, logic, library, resource, local_library, permissions, and metadata) for safe rollback/undo.
Actions:
- 'list': list all available checkpoints for this project (newest first).
- 'create': create a named checkpoint snapshot before making major changes ('label').
- 'restore': rollback the entire project to a checkpoint ('checkpoint_id' or 'latest') and refresh the UI!
- 'delete': delete a specific checkpoint by 'checkpoint_id'.""",
            parameters = listOf(
                ToolParameterProperty("action", "string", "Action: 'list', 'create', 'restore', or 'delete'.", required = true, enumValues = listOf("list", "create", "restore", "delete")),
                ToolParameterProperty("checkpoint_id", "string", "Checkpoint ID (from 'list') or 'latest' when action='restore'/'delete'.", required = false),
                ToolParameterProperty("label", "string", "Short descriptive label when action='create' (e.g. 'before_refactor_auth').", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "preview_generated_source",
            description = "Generate and inspect the complete Java or XML source file that Sketchware will pass to the compiler (e.g. 'MainActivity.java' or 'main.xml'). Essential for verifying generated code before or during debugging.",
            parameters = listOf(
                ToolParameterProperty("file_name", "string", "File name to preview, e.g. 'MainActivity.java' or 'main.xml'.", required = true)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "inspect_runtime_logs",
            description = "Inspect captured runtime broadcast logs (SketchLogger), unresolved Force Close / Runtime Crash stack traces with Java line context, and recent AndroidRuntime logcat errors for this project's compiled app.",
            parameters = listOf(
                ToolParameterProperty("clear_crash", "boolean", "If true, clears the stored crash report after reading (default false).", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "update_long_horizon_plan",
            description = "Create or update the Long-Horizon Task Board (`plan.json`) and persistent working memory (`memory.md`). Always call this at the start of a multi-step build and as you complete milestones.",
            parameters = listOf(
                ToolParameterProperty("goal", "string", "Overall goal of the long-horizon task.", required = true),
                ToolParameterProperty("tasks_json", "string", "JSON array of tasks: `[{\"id\":\"1\",\"title\":\"Design main.xml layout\",\"milestone\":\"UI\",\"status\":\"COMPLETED\"}]`. Status must be PENDING, IN_PROGRESS, COMPLETED, or FAILED.", required = true),
                ToolParameterProperty("memory_notes", "string", "Optional architectural notes, View IDs, Variable names, and conventions to persist in memory.md.", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "save_and_verify_compile",
            description = "Save all project changes to disk and run Sketchware Pro's compiler pipeline (Resource generation + AAPT2 + ViewBinding + Java ECJ compiler, or full signed APK build) to verify zero compile errors. Returns detailed line-level error context if any error occurs.",
            parameters = listOf(
                ToolParameterProperty("full_apk_build", "boolean", "If false (default), runs fast resource + Java compilation verification (steps 1..13). If true, runs full DEX + APK packaging + signing (steps 1..20).", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "run_shell",
            description = """Execute an unrestricted shell command for the agent to work across the whole project workspace.
Engines: the host system shell ('/system/bin/sh', toybox: find, grep, sed, cp, mv, rm, mkdir, pm, am, logcat, getprop) by default; the Linux userland via proot when installed (apt, pip, npm, npx, node, git); or a root shell when 'use_root'=true.
No command allowlist is applied. Use it to install packages, scaffolds, MCP servers and skills, or to run arbitrary build/analysis commands.
Examples: 'find . -name "*.java" | xargs grep -n TODO', 'npm install -g @modelcontextprotocol/server-filesystem', 'pip install requests', 'ls -la', 'getprop ro.product.cpu.abi'.
Working directory defaults to the active project's generated workspace under '.sketchware/mysc/<sc_id>'.""",
            parameters = listOf(
                ToolParameterProperty("command", "string", "Shell command line to execute.", required = true),
                ToolParameterProperty("cwd", "string", "Working directory (absolute). Defaults to the project workspace.", required = false),
                ToolParameterProperty("timeout_ms", "integer", "Timeout in milliseconds (default 120000, max 900000).", required = false),
                ToolParameterProperty("use_root", "boolean", "Run through the root shell (libsu). Default false. Only applies to host or proot execution.", required = false),
                ToolParameterProperty("in_linux", "boolean", "Force running inside the Linux userland via proot. Omit to auto-select (userland if installed, else host shell).", required = false),
                ToolParameterProperty("linux_distro", "string", "Which installed distro to use inside the userland (e.g. 'alpine', 'ubuntu'). Defaults to the configured default distro.", required = false),
                ToolParameterProperty("pty", "boolean", "Run the command under a real PTY (via util-linux 'script') when available in the Linux distro, so tools that check isatty behave interactively. Requires in_linux=true.", required = false),
                ToolParameterProperty("session_id", "string", "Optional persistent session id from terminal_start: run this command inside that existing session and return its incremental output (unifies one-shot and persistent execution).", required = false),
                ToolParameterProperty("confirm_destructive", "boolean", "Set true ONLY when the user explicitly asked for a destructive command the safety guard blocked (e.g. recursive delete outside .sketchware, mkfs, dd to /dev). Never set it on your own initiative.", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "manage_linux_env",
            description = """Manage the Linux userland (proot + Debian/Ubuntu/Alpine) that unlocks apt-get/apk/pip/npm/npx/git/curl/python3 for the agent.
Actions:
- 'status': report ABI, proot, installed distros, detected toolchains, default distro.
- 'bootstrap': one-click setup for a full Linux environment (default distro='debian'): downloads rootfs if missing, sets it as default, configures DNS + APT IPv4/non-interactive rules, and installs essential packages (ca-certificates, curl, wget, git, python3, python3-pip, unzip, procps).
- 'download': fetch the official proot + rootfs for this device's ABI (SHA-256 verified).
- 'install': extract a proot/rootfs bootstrap from 'source_path' or bundled 'assets/linux/'.
- 'list': list userland binaries of a distro.
- 'use': set the default distro by name ('debian', 'ubuntu', or 'alpine').""",
            parameters = listOf(
                ToolParameterProperty("action", "string", "Action: 'status', 'bootstrap', 'list', 'install', 'download', or 'use'.", required = true, enumValues = listOf("status", "bootstrap", "list", "install", "download", "use")),
                ToolParameterProperty("source_path", "string", "Archive or binary path for action='install' (.zip/.tar/.tar.gz/.tar.xz, or a raw proot ELF). If omitted, bundled assets/linux/ is used.", required = false),
                ToolParameterProperty("distro", "string", "Distro for action='bootstrap'/'list'/'use'/'download': 'debian' (default), 'ubuntu', or 'alpine'.", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "web_search",
            description = """Search the web for documentation, Android/Java/Kotlin APIs, library usage, Maven/Gradle coordinates, GitHub repositories, or error messages.
Returns titles, direct URLs, and snippets. Follow up with 'web_fetch' on any URL to read the full page or documentation.""",
            parameters = listOf(
                ToolParameterProperty("query", "string", "Search query string (e.g. 'OkHttp3 Android example', 'Material3 CardView XML attributes').", required = true),
                ToolParameterProperty("max_results", "integer", "Maximum number of results to return (default 8, max 15).", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "web_fetch",
            description = """Fetch any HTTP/HTTPS URL (documentation page, GitHub raw source file, JSON REST API, Maven metadata, etc.).
Automatically converts HTML pages into clean readable Markdown/text with preserved headings, code blocks, and links, or returns raw body when 'raw'=true. Supports pagination via 'offset' and 'max_chars'.""",
            parameters = listOf(
                ToolParameterProperty("url", "string", "Full URL to fetch (http:// or https://).", required = true),
                ToolParameterProperty("method", "string", "HTTP method: GET (default), POST, PUT, DELETE, HEAD.", required = false),
                ToolParameterProperty("headers_json", "string", "Optional JSON object of custom HTTP headers.", required = false),
                ToolParameterProperty("body", "string", "Optional request body for POST/PUT.", required = false),
                ToolParameterProperty("raw", "boolean", "If true, return raw response text without HTML-to-text conversion (default false).", required = false),
                ToolParameterProperty("max_chars", "integer", "Maximum characters to return (default 20000, max 60000).", required = false),
                ToolParameterProperty("offset", "integer", "Character offset for reading long pages in chunks (default 0).", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "terminal_start",
            description = """Start a persistent interactive shell session (kept alive across tool calls), for REPLs, package installers with prompts, and long-lived workflows.
Engines: 'host' (/system/bin/sh), 'linux' (a shell inside a proot distro, e.g. bash/ash), or 'root' (su). When 'pty'=true and the distro has util-linux 'script', the shell runs under a real pseudo-terminal so interactive programs work.
Returns a session_id used by terminal_send / terminal_read / terminal_stop.""",
            parameters = listOf(
                ToolParameterProperty("engine", "string", "Session engine: 'host', 'linux', or 'root'. Default 'host'.", required = false, enumValues = listOf("host", "linux", "root")),
                ToolParameterProperty("distro", "string", "Distro for engine='linux' (e.g. 'ubuntu'). Defaults to the configured default.", required = false),
                ToolParameterProperty("cwd", "string", "Working directory (absolute). Defaults to the project workspace.", required = false),
                ToolParameterProperty("pty", "boolean", "Allocate a real PTY when supported (engine='linux' with util-linux 'script').", required = false),
                ToolParameterProperty("rows", "integer", "PTY rows (default 24). Native PTY sessions only.", required = false),
                ToolParameterProperty("cols", "integer", "PTY columns (default 80). Use larger values (e.g. 200) to avoid wrapped output.", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "terminal_send",
            description = "Send text to a running terminal session's stdin. By default a trailing newline is appended so the shell executes the line.",
            parameters = listOf(
                ToolParameterProperty("session_id", "string", "Session id from terminal_start.", required = true),
                ToolParameterProperty("input", "string", "Text to send (e.g. 'apt-get update' or 'y' to answer a prompt).", required = true),
                ToolParameterProperty("append_newline", "boolean", "Append '\\n' to input (default true). Set false to send raw keystrokes/control chars.", required = false),
                ToolParameterProperty("confirm_destructive", "boolean", "Set true ONLY when the user explicitly asked for a destructive command the safety guard blocked (e.g. recursive delete outside .sketchware, mkfs, dd to /dev). Never set it on your own initiative.", required = false)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "terminal_read",
            description = "Read output produced by a terminal session since the previous read (incremental). Optionally wait a little for the command to produce output.",
            parameters = listOf(
                ToolParameterProperty("session_id", "string", "Session id from terminal_start.", required = true),
                ToolParameterProperty("wait_ms", "integer", "Wait this many ms before reading, to let the command run (default 400, max 20000).", required = false),
                ToolParameterProperty("wait_for_pattern", "string", "Regex. Block (up to wait_ms, default 10000 when set) until unread output matches it, e.g. '\\[Y/n\\]|\\$ $'. Lets you wait for a prompt without repeated polling.", required = false),
                ToolParameterProperty("idle_ms", "integer", "Return once the session produced output and then stayed silent for this many ms (max 10000). Combine with wait_ms as the overall limit.", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "terminal_stop",
            description = "Terminate a persistent terminal session and its whole process tree (alias: terminal_kill).",
            parameters = listOf(
                ToolParameterProperty("session_id", "string", "Session id from terminal_start.", required = true)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "terminal_resize",
            description = "Resize the pseudo-terminal window of a native-PTY session (rows x cols), e.g. to 40x200 so long lines are not wrapped.",
            parameters = listOf(
                ToolParameterProperty("session_id", "string", "Session id from terminal_start.", required = true),
                ToolParameterProperty("rows", "integer", "New number of rows (5-500).", required = true),
                ToolParameterProperty("cols", "integer", "New number of columns (20-500).", required = true)
            ),
            isWriteTool = true
        ),
        ToolDefinition(
            name = "terminal_list",
            description = "List all active terminal sessions with engine, liveness, exit code, and age.",
            parameters = emptyList(),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "use_skill",
            description = """Manage and read specialized SKILL.md instructions (compatible with Claude Code / Antigravity skills).
Actions:
- 'list': list all installed skills (standalone and plugin-bundled) with id, name, description, and status.
- 'read': read the full SKILL.md markdown instructions for 'skill_id'. Always call this when a task matches a skill in the system prompt!
- 'create': create or update a skill with 'skill_id', 'name', 'description', and 'content' (SKILL.md markdown).
- 'install_url': download and install a SKILL.md from an HTTP/HTTPS URL ('url').
- 'enable' / 'disable': toggle a skill on or off by 'skill_id'.
- 'delete': delete a standalone skill by 'skill_id'.""",
            parameters = listOf(
                ToolParameterProperty("action", "string", "Action: 'list', 'read', 'create', 'install_url', 'enable', 'disable', or 'delete'.", required = true, enumValues = listOf("list", "read", "create", "install_url", "enable", "disable", "delete")),
                ToolParameterProperty("skill_id", "string", "Skill ID or name (required for read/create/enable/disable/delete).", required = false),
                ToolParameterProperty("name", "string", "Display name for action='create'.", required = false),
                ToolParameterProperty("description", "string", "When to use this skill (for action='create').", required = false),
                ToolParameterProperty("content", "string", "Markdown body of SKILL.md (for action='create').", required = false),
                ToolParameterProperty("url", "string", "HTTP/HTTPS URL pointing to a SKILL.md file (for action='install_url').", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "manage_plugins",
            description = """Manage AI Agent plugins (.sketchware/ai/plugins/<id>/plugin.json), which bundle skills, MCP connectors, slash commands, and domain rules.
Actions:
- 'list': list all installed plugins and their status.
- 'enable' / 'disable': enable or disable a plugin (and all its bundled skills/MCP connectors) by 'plugin_id'.
- 'install': install a plugin from a local .zip/folder path or remote URL ('source').
- 'delete': uninstall a plugin by 'plugin_id'.""",
            parameters = listOf(
                ToolParameterProperty("action", "string", "Action: 'list', 'enable', 'disable', 'install', or 'delete'.", required = true, enumValues = listOf("list", "enable", "disable", "install", "delete")),
                ToolParameterProperty("plugin_id", "string", "Plugin ID for enable/disable/delete.", required = false),
                ToolParameterProperty("source", "string", "Local path (.zip or directory) or HTTP/HTTPS URL for action='install'.", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "manage_mcp_connectors",
            description = """Manage outbound MCP (Model Context Protocol) client connectors so the agent can connect to external HTTP or Linux Stdio MCP servers.
Actions:
- 'list': list all configured MCP connectors, status, and discovered tools.
- 'sync': connect to 'server_id' (or all enabled connectors if omitted), run MCP initialize + tools/list, and cache discovered tools.
- 'add_http': register an HTTP/Streamable HTTP MCP server ('server_id', 'name', 'url', optional 'headers_json').
- 'add_stdio': register a Stdio MCP server ('server_id', 'name', 'command', optional 'in_linux' default true).
- 'enable' / 'disable' / 'remove': toggle or remove a connector by 'server_id'.""",
            parameters = listOf(
                ToolParameterProperty("action", "string", "Action: 'list', 'sync', 'add_http', 'add_stdio', 'enable', 'disable', or 'remove'.", required = true, enumValues = listOf("list", "sync", "add_http", "add_stdio", "enable", "disable", "remove")),
                ToolParameterProperty("server_id", "string", "MCP connector ID (e.g. 'debian_toolkit', 'context7').", required = false),
                ToolParameterProperty("name", "string", "Display name when adding a connector.", required = false),
                ToolParameterProperty("url", "string", "Endpoint URL for action='add_http'.", required = false),
                ToolParameterProperty("command", "string", "Shell command for action='add_stdio' (e.g. 'python3 /path/mcp.py' or 'npx -y ...').", required = false),
                ToolParameterProperty("in_linux", "boolean", "For action='add_stdio': run inside Debian Linux proot (default true).", required = false),
                ToolParameterProperty("headers_json", "string", "Optional JSON object of HTTP headers (e.g. Authorization Bearer) for action='add_http'.", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "call_mcp_tool",
            description = "Invoke any tool exposed by a connected external MCP server (HTTP or Linux Stdio).",
            parameters = listOf(
                ToolParameterProperty("server_id", "string", "MCP connector ID (e.g. 'debian_toolkit', 'context7').", required = true),
                ToolParameterProperty("tool_name", "string", "Name of the tool on that MCP server (e.g. 'python_eval', 'sqlite_inspect').", required = true),
                ToolParameterProperty("arguments_json", "string", "JSON object of arguments to pass to the MCP tool.", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "manage_artifacts",
            description = """DeerFlow Workspace Artifacts Manager — list, read, write, or delete tangible deliverables (.md, .html, .json, .xml, .svg, scripts, reports) in the project's Artifacts panel (.sketchware/data/<sc_id>/ai_artifacts/), as well as inspect compiled APKs, layouts, and custom Java sources.""",
            parameters = listOf(
                ToolParameterProperty("action", "string", "Action: 'list', 'read', 'write', or 'delete'.", required = true, enumValues = listOf("list", "read", "write", "delete")),
                ToolParameterProperty("file_name", "string", "Artifact file name (e.g. 'architecture_plan.md', 'ui_preview.html') or absolute path.", required = false),
                ToolParameterProperty("content", "string", "Content to write when action='write'.", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "delegate_subagent",
            description = """DeerFlow Ultra/Pro Sub-Agent Delegation — spawn a specialized sub-agent ('researcher', 'ui_architect', 'block_auditor', 'compile_debugger') with an isolated context window to inspect project screens, logic, or architecture and return a concise report without bloating the main conversation context.""",
            parameters = listOf(
                ToolParameterProperty("role", "string", "Specialist role: 'researcher', 'ui_architect', 'block_auditor', or 'compile_debugger'.", required = true, enumValues = listOf("researcher", "ui_architect", "block_auditor", "compile_debugger")),
                ToolParameterProperty("task", "string", "Clear, specific task or question for the sub-agent to investigate and report on.", required = true),
                ToolParameterProperty("target_screens", "string", "Optional comma-separated screen names (e.g. 'main,login') to preload into the sub-agent context.", required = false)
            ),
            isWriteTool = false
        ),
        ToolDefinition(
            name = "reconstruct_apk_project",
            description = """REA (Reverse Engineer Anything) & Full APK Project Cloner — inspect any .apk file, decompile binary AndroidManifest.xml and res/layout/*.xml back into readable XML, analyze classes*.dex bytecode (classes, methods, fields, URLs, SQL/API strings), or perform a 'full_clone' that automatically clones all APK layouts into Sketchware screens/custom views, imports drawables/assets/fonts/permissions, and generates the REA Clone Blueprint artifact.""",
            parameters = listOf(
                ToolParameterProperty(
                    "action",
                    "string",
                    "Action to perform: 'inspect' (deep APK + manifest + DEX overview), 'decode_xml' (decompile binary AndroidManifest.xml or res/layout/*.xml), 'analyze_dex' (inspect DEX classes, methods, fields, and strings), or 'full_clone' (automatically clone layouts, drawables, assets, fonts, permissions, and blueprint into the current Sketchware project).",
                    required = true,
                    enumValues = listOf("inspect", "decode_xml", "analyze_dex", "full_clone")
                ),
                ToolParameterProperty(
                    "apk_path",
                    "string",
                    "Path to the .apk file (e.g. from an attached APK context item in .sketchware/data/<sc_id>/files/context_attachments/<name>.apk or /storage/emulated/0/Download/<name>.apk).",
                    required = true
                ),
                ToolParameterProperty(
                    "xml_entry",
                    "string",
                    "For action='decode_xml': entry path inside the APK such as 'AndroidManifest.xml' or 'res/layout/activity_main.xml'.",
                    required = false
                ),
                ToolParameterProperty(
                    "query",
                    "string",
                    "For action='analyze_dex': optional filter keyword or class/package substring (e.g. 'MainActivity', 'Login', 'api', 'db').",
                    required = false
                ),
                ToolParameterProperty(
                    "update_app_identity",
                    "boolean",
                    "For action='full_clone': whether to also update the current Sketchware project's app name & version to match the source APK (default false).",
                    required = false
                )
            ),
            isWriteTool = false
        )
    )

    fun getToolsForMode(mode: AgentAutonomyMode): List<ToolDefinition> {
        val base = if (mode.allowsWriteTools) {
            allTools
        } else {
            allTools.filter { !it.isWriteTool }
        }
        val dynamicMcp = try {
            McpClientHub.getDynamicToolDefinitions()
        } catch (_: Throwable) {
            emptyList()
        }
        return base + dynamicMcp
    }

    /**
     * Whether [toolName] can mutate the project, the filesystem, or the agent's own state.
     * Used as a defense-in-depth guard at execution time so read-only modes cannot run a
     * mutating tool even if a model emits a call for a tool it was not offered.
     */
    fun isWriteTool(toolName: String): Boolean {
        val canonical = TOOL_ALIASES[toolName] ?: toolName
        return allTools.firstOrNull { it.name == canonical }?.isWriteTool ?: false
    }

    /** Alternate tool names models commonly emit -> the canonical registered tool. */
    private val TOOL_ALIASES: Map<String, String> = mapOf(
        "terminal_kill" to "terminal_stop",
        "terminal_close" to "terminal_stop"
    )

    fun executeTool(
        context: Context,
        scId: String,
        toolName: String,
        args: JsonObject,
        onProgressUpdate: ((String) -> Unit)? = null
    ): String {
        return try {
            when (TOOL_ALIASES[toolName] ?: toolName) {
                "get_project_summary" -> ProjectGateway.getProjectSummary(scId)

                "get_screen_state" -> {
                    val screen = args.str("screen_name").ifEmpty { "main" }
                    ProjectGateway.getScreenStateDetails(scId, screen)
                }

                "read_screen_xml" -> {
                    val screen = args.str("screen_name").ifEmpty { "main" }
                    ProjectGateway.readScreenXml(context, scId, screen)
                }

                "write_screen_xml" -> {
                    val screen = args.str("screen_name").ifEmpty { "main" }
                    val xml = args.str("xml_content")
                    if (xml.isBlank()) {
                        return "Error: parameter 'xml_content' kosong."
                    }
                    ProjectGateway.writeScreenXml(scId, screen, xml, true)
                }

                "create_screen" -> {
                    val name = args.str("screen_name")
                    val isCustom = args.bool("is_custom_view", false)
                    val hasToolbar = args.bool("has_toolbar", true)
                    val isFullscreen = args.bool("is_fullscreen", false)
                    val hasFab = args.bool("has_fab", false)
                    val hasDrawer = args.bool("has_drawer", false)
                    val bean = ProjectGateway.createScreenOrCustomView(
                        scId = scId,
                        rawName = name,
                        isCustomView = isCustom,
                        hasToolbar = hasToolbar,
                        isFullscreen = isFullscreen,
                        hasFab = hasFab,
                        hasDrawer = hasDrawer
                    )
                    "Berhasil membuat ${if (isCustom) "CustomView" else "Activity Screen"}: fileName='${bean.fileName}', xmlName='${bean.xmlName}', javaName='${bean.javaName}'."
                }

                "manage_variable" -> {
                    val screen = args.str("screen_name").ifEmpty { "main" }
                    val action = args.str("action").ifEmpty { "add" }
                    val varName = args.str("var_name")
                    val varType = args.str("var_type").ifEmpty { "string" }
                    ProjectGateway.manageVariable(scId, screen, action, varName, varType)
                }

                "manage_list" -> {
                    val screen = args.str("screen_name").ifEmpty { "main" }
                    val action = args.str("action").ifEmpty { "add" }
                    val listName = args.str("list_name")
                    val listType = args.str("list_type").ifEmpty { "string" }
                    ProjectGateway.manageList(scId, screen, action, listName, listType)
                }

                "add_component" -> {
                    val screen = args.str("screen_name").ifEmpty { "main" }
                    val compType = args.str("component_type")
                    val compId = args.str("component_id")
                    val param1 = args.str("param1")
                    ProjectGateway.addComponent(scId, screen, compType, compId, param1)
                }

                "create_more_block" -> {
                    val screen = args.str("screen_name").ifEmpty { "main" }
                    val funcName = args.str("func_name")
                    val spec = args.str("spec")
                    val returnType = args.str("return_type").ifEmpty { "void" }
                    ProjectGateway.createMoreBlock(scId, screen, funcName, spec, returnType)
                }

                "search_blocks_catalog" -> {
                    val query = args.str("query")
                    val matches = BlockCatalog.search(query, 45)
                    if (matches.isEmpty()) {
                        "Tidak ditemukan blok untuk kata kunci '$query'. Anda selalu bisa menggunakan opCode 'addSourceDirectly' untuk kode Java kustom."
                    } else {
                        buildString {
                            appendLine("Ditemukan ${matches.size} blok untuk '$query':")
                            for (m in matches) {
                                appendLine("- opCode=\"${m.opCode}\" | type=\"${m.type}\" | spec=\"${m.spec}\" | ${m.description}")
                            }
                        }.trim()
                    }
                }

                "read_event_logic" -> {
                    val screen = args.str("screen_name").ifEmpty { "main" }
                    val targetId = args.str("target_id").ifEmpty { "onCreate" }
                    val eventName = args.str("event_name").ifEmpty { "initializeLogic" }
                    ProjectGateway.readEventLogic(scId, screen, targetId, eventName)
                }

                "write_event_logic" -> {
                    val screen = args.str("screen_name").ifEmpty { "main" }
                    val targetId = args.str("target_id").ifEmpty { "onCreate" }
                    val eventName = args.str("event_name").ifEmpty { "initializeLogic" }
                    val blocksInput = args.str("blocks_input").ifEmpty { args.str("blocks") }
                    val append = args.bool("append", false)
                    ProjectGateway.writeEventLogic(context, scId, screen, targetId, eventName, blocksInput, append)
                }

                "configure_library" -> {
                    val lib = args.str("library_name")
                    val enabled = args.bool("enabled", true)
                    ProjectGateway.setLibraryEnabled(scId, lib, enabled)
                }

                "add_permissions" -> {
                    val perms = args.str("permissions")
                        .split(Regex("[,;\\s]+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    ProjectGateway.addPermissions(scId, perms)
                }

                "add_custom_imports" -> {
                    val imps = args.str("imports")
                        .split(Regex("[,;\\n]+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    ProjectGateway.addCustomImports(scId, imps)
                }

                "write_custom_java_file" -> {
                    val fileName = args.str("file_name")
                    val code = args.str("source_code")
                    ProjectGateway.writeCustomJavaFile(scId, fileName, code)
                }

                "manage_sketchware_files" -> {
                    val action = args.str("action")
                    val path = args.str("path")
                    val content = args.str("content")
                    val targetPath = args.str("target_path")
                    val recursive = args.bool("recursive", true)
                    ProjectGateway.manageSketchwareFiles(scId, action, path, content, targetPath, recursive)
                }

                "manage_local_libraries" -> {
                    val action = args.str("action")
                    val libraryNames = args.str("library_names")
                    val mavenCoord = args.str("maven_coordinate")
                    val skipSub = args.bool("skip_subdependencies", true)
                    val srcPath = args.str("source_path")
                    val pkgName = args.str("package_name")
                    if (action.equals("install_maven", ignoreCase = true)) {
                        onProgressUpdate?.invoke("Mengunduh & mengompilasi Maven '$mavenCoord' via D8…")
                    }
                    ProjectGateway.manageLocalLibraries(
                        scId = scId,
                        action = action,
                        libraryNames = libraryNames,
                        mavenCoordinate = mavenCoord,
                        skipSubdependencies = skipSub,
                        sourcePath = srcPath,
                        packageName = pkgName
                    )
                }

                "manage_project_resources" -> {
                    val action = args.str("action").ifEmpty { "list" }
                    val resType = args.str("resource_type").ifEmpty { "image" }
                    val resName = args.str("resource_name")
                    val content = args.str("content")
                    val sourcePath = args.str("source_path")
                    ProjectGateway.manageProjectResources(
                        scId = scId,
                        action = action,
                        resourceType = resType,
                        resourceName = resName,
                        content = content,
                        sourcePath = sourcePath
                    )
                }

                "configure_project_metadata" -> {
                    ProjectGateway.configureProjectMetadata(
                        context = context,
                        scId = scId,
                        action = args.str("action").ifEmpty { "read" },
                        appName = args.str("app_name"),
                        packageName = args.str("package_name"),
                        workspaceName = args.str("workspace_name"),
                        versionCode = args.str("version_code"),
                        versionName = args.str("version_name"),
                        colorPrimaryHex = args.str("color_primary"),
                        colorPrimaryDarkHex = args.str("color_primary_dark"),
                        colorAccentHex = args.str("color_accent"),
                        minSdk = args.str("min_sdk"),
                        targetSdk = args.str("target_sdk"),
                        javaVersion = args.str("java_version"),
                        iconSourcePath = args.str("icon_source_path"),
                        screenToDelete = args.str("screen_to_delete"),
                        eventScreen = args.str("event_screen"),
                        eventTargetId = args.str("event_target_id"),
                        eventNameToDelete = args.str("event_name_to_delete")
                    )
                }

                "search_project_code" -> {
                    val query = args.str("query")
                    val caseSensitive = args.bool("case_sensitive", false)
                    ProjectGateway.searchProjectCode(scId, query, caseSensitive)
                }

                "manage_checkpoints" -> {
                    val action = args.str("action").ifEmpty { "list" }
                    val cpId = args.str("checkpoint_id")
                    val label = args.str("label").ifEmpty { "manual" }
                    ProjectGateway.manageCheckpoints(scId, action, cpId, label)
                }

                "preview_generated_source" -> {
                    val fileName = args.str("file_name").ifEmpty { "MainActivity.java" }
                    ProjectGateway.previewGeneratedFileSource(context, scId, fileName)
                }

                "inspect_runtime_logs" -> {
                    RuntimeLogHarness.ensureRegistered(context)
                    val clearCrash = args.bool("clear_crash", false)
                    RuntimeLogHarness.inspectRuntimeLogs(scId, clearCrash)
                }

                "update_long_horizon_plan" -> {
                    val goal = args.str("goal")
                    val tasksJson = args.str("tasks_json")
                    val memoryNotes = args.str("memory_notes")

                    val tasks = mutableListOf<AgentTaskItem>()
                    try {
                        val arr: JsonArray = JsonParser.parseString(tasksJson).asJsonArray
                        for ((idx, el) in arr.withIndex()) {
                            val obj = el.asJsonObject
                            val id = obj.get("id")?.asString ?: "${idx + 1}"
                            val title = obj.get("title")?.asString ?: "Task ${idx + 1}"
                            val milestone = obj.get("milestone")?.asString ?: "General"
                            val statusStr = obj.get("status")?.asString?.uppercase(Locale.US) ?: "PENDING"
                            val status = try {
                                TaskStatus.valueOf(statusStr)
                            } catch (_: Exception) {
                                TaskStatus.PENDING
                            }
                            val notes = obj.get("notes")?.asString.orEmpty()
                            tasks.add(AgentTaskItem(id, title, milestone, status, notes))
                        }
                    } catch (_: Exception) {
                    }

                    val plan = AgentPlan(goal = goal, tasks = tasks)
                    AgentMemoryStore.savePlan(scId, plan)
                    if (memoryNotes.isNotBlank()) {
                        AgentMemoryStore.saveMemoryNotes(scId, memoryNotes, append = false)
                    }
                    val completed = tasks.count { it.status == TaskStatus.COMPLETED }
                    "Long-Horizon Plan diperbarui ($completed/${tasks.size} task selesai)."
                }

                "save_and_verify_compile" -> {
                    RuntimeLogHarness.ensureRegistered(context)
                    val fullApk = args.bool("full_apk_build", false)
                    onProgressUpdate?.invoke(if (fullApk) "Menjalankan Full APK Build..." else "Memverifikasi kompilasi Resource & Java...")
                    val res = HeadlessProjectBuilder.buildProject(
                        context = context,
                        scId = scId,
                        compileOnlyCheck = !fullApk
                    ) { stage, step ->
                        onProgressUpdate?.invoke("Build [$step/20]: $stage")
                    }
                    buildString {
                        if (res.success) {
                            RuntimeLogHarness.clearCrashReport(scId)
                            appendLine("BUILD SUKSES! (${res.durationMs} ms)")
                            appendLine("Stage: ${res.stageReached}")
                            if (!res.apkPath.isNullOrEmpty()) {
                                appendLine("APK Path: ${res.apkPath}")
                            }
                        } else {
                            appendLine("BUILD GAGAL pada tahap: ${res.stageReached} (${res.durationMs} ms)")
                            appendLine("\n=== COMPILER ERROR LOG ===")
                            appendLine(res.errorSummary ?: "Unknown error")
                            if (!res.sourceSnippetWithContext.isNullOrEmpty()) {
                                appendLine("\n=== SOURCE CODE CONTEXT AROUND ERROR ===")
                                appendLine(res.sourceSnippetWithContext)
                            }
                            appendLine("\n Analisis baris yang gagal di atas, gunakan `preview_generated_source` atau `read_event_logic`/`read_screen_xml` jika perlu, perbaiki blok/layout yang salah, lalu jalankan `save_and_verify_compile` kembali.")
                        }
                    }.trim()
                }

                "run_shell" -> {
                    val command = args.str("command")
                    if (command.isBlank()) {
                        return "Error: parameter 'command' kosong."
                    }
                    guardDestructive(command, args)?.let { return it }
                    val sessionId = args.str("session_id")
                    if (sessionId.isNotBlank()) {
                        val session = TerminalSessionManager.get(sessionId)
                            ?: return "Error: session '$sessionId' tidak ditemukan. Pakai terminal_list untuk melihat sesi aktif."
                        val waitMs = args.long("timeout_ms", 5_000L).coerceIn(0L, 60_000L)
                        onProgressUpdate?.invoke("run_shell [session:$sessionId]: ${command.take(80)}")
                        session.write(command + "\n")
                        if (waitMs > 0) {
                            try {
                                Thread.sleep(waitMs)
                            } catch (_: InterruptedException) {
                            }
                        }
                        val chunk = session.readNew()
                        val out = buildString {
                            appendLine("=== run_shell [session:$sessionId | ${session.engine} | alive=${session.isAlive}] ===")
                            if (chunk.isBlank()) {
                                appendLine("(belum ada output baru; proses " + (if (session.isAlive) "masih berjalan — ulangi terminal_read/run_shell untuk polling" else "sudah selesai") + ")")
                            } else {
                                append(chunk)
                            }
                        }.trim()
                        return OutputSpiller.maybeSpill(scId, "run_shell_session", out)
                    }
                    val cwdArg = args.str("cwd")
                    val cwd = if (cwdArg.isNotBlank()) File(cwdArg) else defaultWorkspaceDir(scId)
                    val timeoutMs = args.long("timeout_ms", 120_000L)
                    val useRoot = args.bool("use_root", false)
                    val inLinux = args.bool("in_linux", LinuxSubsystem.isReady())
                    val distro = args.str("linux_distro").ifBlank { null }
                    val pty = args.bool("pty", false)
                    val engineLabel = if (inLinux) "linux:${distro ?: LinuxSubsystem.defaultDistro() ?: "?"}"
                        else if (useRoot) "root" else "host"
                    onProgressUpdate?.invoke("run_shell [$engineLabel]: ${command.take(80)}")
                    formatShellResult(ShellRunner.run(command, cwd, timeoutMs, useRoot, inLinux, distro, pty), scId)
                }

                "terminal_start" -> {
                    val engine = args.str("engine").ifEmpty { "host" }
                    val distro = args.str("distro").ifBlank { null }
                    val cwdArg = args.str("cwd")
                    val cwd = if (cwdArg.isNotBlank()) File(cwdArg) else defaultWorkspaceDir(scId)
                    val pty = args.bool("pty", false)
                    val rows = args.long("rows", TerminalSessionManager.DEFAULT_ROWS.toLong()).toInt()
                    val cols = args.long("cols", TerminalSessionManager.DEFAULT_COLS.toLong()).toInt()
                    onProgressUpdate?.invoke("terminal_start [$engine${distro?.let { ":$it" } ?: ""}]")
                    try {
                        val session = TerminalSessionManager.start(context, engine, distro, cwd, pty, rows, cols)
                        "Session dimulai: ${session.id}\n${session.describe()}\n\nGunakan terminal_send(session_id=\"${session.id}\", input=\"...\") lalu terminal_read untuk mengambil output."
                    } catch (t: Throwable) {
                        "Error: gagal memulai terminal: ${t.javaClass.simpleName}: ${t.message}"
                    }
                }

                "terminal_send" -> {
                    val sessionId = args.str("session_id")
                    val input = args.str("input")
                    val appendNewline = args.bool("append_newline", true)
                    guardDestructive(input, args)?.let { return it }
                    val session = TerminalSessionManager.get(sessionId)
                        ?: return "Error: session '$sessionId' tidak ditemukan. Pakai terminal_list untuk melihat sesi aktif."
                    session.write(if (appendNewline) input + "\n" else input)
                    "Terkirim ke $sessionId (${input.length} char, newline=$appendNewline). Lanjutkan dengan terminal_read."
                }

                "terminal_read" -> {
                    val sessionId = args.str("session_id")
                    val session = TerminalSessionManager.get(sessionId)
                        ?: return "Error: session '$sessionId' tidak ditemukan."
                    val pattern = args.str("wait_for_pattern")
                    val idleMs = args.long("idle_ms", 0L).coerceIn(0L, 10_000L)
                    val waiting = pattern.isNotBlank() || idleMs > 0L
                    val waitMs = args.long("wait_ms", if (waiting) 10_000L else 400L)
                        .coerceIn(0L, if (waiting) 120_000L else 20_000L)
                    val regex = if (pattern.isNotBlank()) {
                        try {
                            Regex(pattern)
                        } catch (e: Exception) {
                            return "Error: wait_for_pattern bukan regex yang valid: ${e.message}"
                        }
                    } else {
                        null
                    }
                    val chunk = if (waiting) {
                        session.readUntil(regex, waitMs, idleMs)
                    } else {
                        if (waitMs > 0) {
                            try {
                                Thread.sleep(waitMs)
                            } catch (_: InterruptedException) {
                            }
                        }
                        session.readNew()
                    }
                    val promptHint = if (session.isAlive) PromptDetector.detect(chunk) else null
                    val out = buildString {
                        appendLine("=== terminal_read [$sessionId | ${session.engine} | alive=${session.isAlive} | exit=${session.exitCode ?: "-"}] ===")
                        if (chunk.isBlank()) appendLine("(tidak ada output baru; proses " + (if (session.isAlive) "masih berjalan" else "sudah selesai") + ")")
                        else append(chunk)
                        if (promptHint != null) {
                            appendLine()
                            appendLine("[prompt] $promptHint")
                        }
                    }.trim()
                    OutputSpiller.maybeSpill(scId, "terminal_read", out)
                }

                "terminal_stop" -> {
                    val sessionId = args.str("session_id")
                    if (TerminalSessionManager.stop(sessionId)) "Session '$sessionId' dihentikan." else "Error: session '$sessionId' tidak ditemukan."
                }

                "terminal_resize" -> {
                    val sessionId = args.str("session_id")
                    val session = TerminalSessionManager.get(sessionId)
                        ?: return "Error: session '$sessionId' tidak ditemukan."
                    val rows = args.long("rows", 0L).toInt()
                    val cols = args.long("cols", 0L).toInt()
                    if (rows <= 0 || cols <= 0) return "Error: 'rows' dan 'cols' wajib diisi (angka positif)."
                    if (session.resize(rows, cols)) "Ukuran PTY '$sessionId' diubah menjadi ${rows}x$cols."
                    else "Error: sesi '$sessionId' bukan sesi native-PTY, sehingga tidak bisa di-resize."
                }

                "terminal_list" -> {
                    val list = TerminalSessionManager.list()
                    if (list.isEmpty()) {
                        "Tidak ada sesi terminal aktif."
                    } else {
                        buildString {
                            appendLine("Sesi terminal aktif (${list.size}):")
                            list.forEach { appendLine("- ${it.describe()}") }
                        }.trim()
                    }
                }

                "manage_linux_env" -> {
                    when (args.str("action").ifEmpty { "status" }) {
                        "status" -> LinuxSubsystem.statusReport()
                        "list" -> LinuxSubsystem.listUserlandBinaries(args.str("distro").ifBlank { null })
                        "install" -> {
                            val sourcePath = args.str("source_path")
                            if (sourcePath.isBlank()) {
                                LinuxSubsystem.installFromAssets()
                            } else {
                                LinuxSubsystem.installArchive(File(sourcePath))
                            }
                        }
                        "download" -> {
                            val distro = args.str("distro").ifBlank { "debian" }
                            onProgressUpdate?.invoke("Mengunduh userland '$distro' (${LinuxSubsystem.deviceArchTag()})…")
                            LinuxDownloader.downloadAndInstall(context, distro) { msg ->
                                onProgressUpdate?.invoke(msg)
                            }
                        }
                        "bootstrap" -> {
                            val distro = args.str("distro").ifBlank { "debian" }
                            onProgressUpdate?.invoke("Bootstrap userland '$distro' & memasang toolchain…")
                            LinuxDownloader.bootstrapToolchain(context, distro) { msg ->
                                onProgressUpdate?.invoke(msg)
                            }
                        }
                        "use" -> {
                            val distro = args.str("distro")
                            if (distro.isBlank()) {
                                "Error: parameter 'distro' wajib diisi untuk action='use'."
                            } else if (LinuxSubsystem.setDefaultDistro(distro)) {
                                "Distro default diset ke '$distro'."
                            } else {
                                "Error: distro '$distro' belum terpasang. Terpasang: " +
                                    LinuxSubsystem.installedDistros().joinToString(", ").ifEmpty { "(kosong)" }
                            }
                        }
                        else -> "Error: action '${args.str("action")}' tidak dikenal (pakai status/bootstrap/list/install/download/use)."
                    }
                }

                "web_search" -> {
                    val query = args.str("query")
                    val maxResults = args.long("max_results", 8L).toInt()
                    onProgressUpdate?.invoke("Mencari di web: ${query.take(60)}…")
                    val out = WebTools.search(query, maxResults)
                    OutputSpiller.maybeSpill(scId, "web_search", out)
                }

                "web_fetch" -> {
                    val url = args.str("url")
                    val method = args.str("method").ifBlank { "GET" }
                    val headersJson = args.str("headers_json").ifBlank { null }
                    val headers = mutableMapOf<String, String>()
                    if (!headersJson.isNullOrBlank()) {
                        try {
                            val obj = JsonParser.parseString(headersJson).asJsonObject
                            for ((k, v) in obj.entrySet()) {
                                if (v.isJsonPrimitive) headers[k] = v.asString
                            }
                        } catch (_: Exception) {
                        }
                    }
                    val body = args.str("body").ifBlank { null }
                    val raw = args.bool("raw", false)
                    val maxChars = args.long("max_chars", 20_000L).toInt()
                    val offset = args.long("offset", 0L).toInt()
                    onProgressUpdate?.invoke("Mengambil URL: ${url.take(70)}…")
                    val out = WebTools.fetch(
                        url = url,
                        method = method,
                        headers = headers,
                        body = body,
                        raw = raw,
                        maxChars = maxChars,
                        offset = offset
                    )
                    OutputSpiller.maybeSpill(scId, "web_fetch", out)
                }

                "use_skill" -> {
                    when (val action = args.str("action").ifBlank { "list" }.lowercase()) {
                        "list" -> {
                            val list = SkillManager.listAllSkills()
                            buildString {
                                appendLine("=== DAFTAR SKILL (${list.size}) ===")
                                for (s in list) {
                                    appendLine("- [${if (s.enabled) "ON" else "OFF"}] ${s.id} (${s.name}) [${s.source}]")
                                    appendLine("  Deskripsi : ${s.description}")
                                    appendLine("  Lokasi    : ${s.skillFilePath}")
                                }
                            }.trim()
                        }
                        "read" -> {
                            val id = args.str("skill_id")
                            if (id.isBlank()) "Error: parameter 'skill_id' wajib diisi untuk action='read'."
                            else SkillManager.readSkillContent(id)
                        }
                        "create" -> {
                            val id = args.str("skill_id").ifBlank { args.str("name") }
                            val name = args.str("name").ifBlank { id }
                            val desc = args.str("description").ifBlank { "Skill $name" }
                            val content = args.str("content")
                            if (id.isBlank() || content.isBlank()) {
                                "Error: parameter 'skill_id'/'name' dan 'content' wajib diisi untuk action='create'."
                            } else {
                                val created = SkillManager.createOrUpdateSkill(id, name, desc, content)
                                "Skill '${created.name}' (${created.id}) berhasil disimpan di ${created.skillFilePath}"
                            }
                        }
                        "install_url" -> {
                            val url = args.str("url")
                            val id = args.str("skill_id").ifBlank { null }
                            if (url.isBlank()) "Error: parameter 'url' wajib diisi untuk action='install_url'."
                            else SkillManager.installSkillFromUrl(url, id)
                        }
                        "enable", "disable" -> {
                            val id = args.str("skill_id")
                            val en = action == "enable"
                            if (SkillManager.setSkillEnabled(id, en)) "Skill '$id' diubah ke ${if (en) "AKTIF" else "NONAKTIF"}."
                            else "Error: Skill '$id' tidak ditemukan."
                        }
                        "delete" -> {
                            val id = args.str("skill_id")
                            if (SkillManager.deleteSkill(id)) "Skill '$id' berhasil dihapus."
                            else "Error: Skill '$id' tidak ditemukan atau berasal dari plugin."
                        }
                        else -> "Error: action '$action' tidak dikenal pada use_skill."
                    }
                }

                "manage_plugins" -> {
                    when (val action = args.str("action").ifBlank { "list" }.lowercase()) {
                        "list" -> {
                            val plugins = PluginManager.listPlugins()
                            buildString {
                                appendLine("=== DAFTAR PLUGIN (${plugins.size}) ===")
                                for (p in plugins) {
                                    appendLine("- [${if (p.enabled) "ON" else "OFF"}] ${p.id} (${p.name} v${p.version})")
                                    appendLine("  Deskripsi : ${p.description}")
                                    if (p.skillIds.isNotEmpty()) appendLine("  Skills    : ${p.skillIds.joinToString(", ")}")
                                    if (p.slashCommands.isNotEmpty()) appendLine("  Slash Cmd : ${p.slashCommands.joinToString(", ") { it.command }}")
                                    if (p.mcpConnectorIds.isNotEmpty()) appendLine("  MCP       : ${p.mcpConnectorIds.joinToString(", ")}")
                                }
                            }.trim()
                        }
                        "enable", "disable" -> {
                            val id = args.str("plugin_id")
                            val en = action == "enable"
                            if (PluginManager.setPluginEnabled(id, en)) "Plugin '$id' diubah ke ${if (en) "AKTIF" else "NONAKTIF"}."
                            else "Error: Plugin '$id' tidak ditemukan."
                        }
                        "install" -> {
                            val src = args.str("source")
                            if (src.isBlank()) "Error: parameter 'source' wajib diisi untuk action='install'."
                            else PluginManager.installPluginFromArchiveOrUrl(src)
                        }
                        "delete" -> {
                            val id = args.str("plugin_id")
                            if (PluginManager.deletePlugin(id)) "Plugin '$id' berhasil dihapus."
                            else "Error: Plugin '$id' tidak ditemukan."
                        }
                        else -> "Error: action '$action' tidak dikenal pada manage_plugins."
                    }
                }

                "manage_mcp_connectors" -> {
                    when (val action = args.str("action").ifBlank { "list" }.lowercase()) {
                        "list" -> {
                            val list = McpClientHub.listConnectors()
                            buildString {
                                appendLine("=== DAFTAR KONEKTOR MCP (${list.size}) ===")
                                for (c in list) {
                                    val target = if (c.transport == "http") c.url else c.command
                                    appendLine("- [${if (c.enabled) "ON" else "OFF"}] ${c.id} (${c.name} | ${c.transport.uppercase()}) -> $target")
                                    appendLine("  Status : ${c.lastStatus}")
                                    if (c.cachedTools.isNotEmpty()) {
                                        appendLine("  Tools  : ${c.cachedTools.joinToString(", ") { "${it.name} (${it.qualifiedName})" }}")
                                    }
                                }
                            }.trim()
                        }
                        "sync" -> {
                            val id = args.str("server_id")
                            if (id.isNotBlank()) {
                                onProgressUpdate?.invoke("Sinkronisasi MCP '$id'…")
                                McpClientHub.syncConnectorTools(id)
                            } else {
                                val enabled = McpClientHub.listConnectors().filter { it.enabled }
                                enabled.joinToString("\n\n") { McpClientHub.syncConnectorTools(it.id) }
                                    .ifBlank { "Tidak ada konektor MCP yang aktif." }
                            }
                        }
                        "add_http" -> {
                            val name = args.str("name").ifBlank { args.str("server_id") }
                            val id = args.str("server_id").ifBlank { name }
                            val url = args.str("url")
                            if (id.isBlank() || url.isBlank()) {
                                return "Error: parameter 'server_id'/'name' dan 'url' wajib diisi untuk add_http."
                            }
                            val headers = mutableMapOf<String, String>()
                            val hJson = args.str("headers_json")
                            if (hJson.isNotBlank()) {
                                try {
                                    val obj = JsonParser.parseString(hJson).asJsonObject
                                    obj.entrySet().forEach { (k, v) -> if (v.isJsonPrimitive) headers[k] = v.asString }
                                } catch (_: Throwable) {
                                }
                            }
                            val saved = McpClientHub.addOrUpdateConnector(
                                McpConnectorConfig(id = id, name = name, transport = "http", url = url, headers = headers, enabled = true)
                            )
                            McpClientHub.syncConnectorTools(saved.id)
                        }
                        "add_stdio" -> {
                            val name = args.str("name").ifBlank { args.str("server_id") }
                            val id = args.str("server_id").ifBlank { name }
                            val cmd = args.str("command")
                            val inLinux = args.bool("in_linux", true)
                            if (id.isBlank() || cmd.isBlank()) {
                                return "Error: parameter 'server_id'/'name' dan 'command' wajib diisi untuk add_stdio."
                            }
                            val saved = McpClientHub.addOrUpdateConnector(
                                McpConnectorConfig(id = id, name = name, transport = "stdio", command = cmd, inLinux = inLinux, enabled = true)
                            )
                            McpClientHub.syncConnectorTools(saved.id)
                        }
                        "enable", "disable" -> {
                            val id = args.str("server_id")
                            val en = action == "enable"
                            if (McpClientHub.setConnectorEnabled(id, en)) "Konektor MCP '$id' diubah ke ${if (en) "AKTIF" else "NONAKTIF"}."
                            else "Error: Konektor MCP '$id' tidak ditemukan."
                        }
                        "remove" -> {
                            val id = args.str("server_id")
                            if (McpClientHub.removeConnector(id)) "Konektor MCP '$id' berhasil dihapus."
                            else "Error: Konektor MCP '$id' tidak ditemukan."
                        }
                        else -> "Error: action '$action' tidak dikenal pada manage_mcp_connectors."
                    }
                }

                "call_mcp_tool" -> {
                    val serverId = args.str("server_id")
                    val rTool = args.str("tool_name")
                    val rawArgs = args.str("arguments_json")
                    val callArgs = if (rawArgs.isNotBlank()) {
                        try {
                            JsonParser.parseString(rawArgs).asJsonObject
                        } catch (_: Throwable) {
                            JsonObject()
                        }
                    } else {
                        JsonObject()
                    }
                    onProgressUpdate?.invoke("Memanggil MCP [$serverId :: $rTool]…")
                    val out = McpClientHub.callRemoteTool(serverId, rTool, callArgs)
                    OutputSpiller.maybeSpill(scId, "mcp_${serverId}_$rTool", out)
                }

                "manage_artifacts" -> {
                    val action = args.str("action").ifBlank { "list" }.lowercase()
                    val fileName = args.str("file_name")
                    val content = args.str("content")
                    when (action) {
                        "list" -> {
                            val items = pro.sketchware.ai.ui.WorkspaceArtifactsManager.collectArtifacts(scId)
                            if (items.isEmpty()) {
                                "Belum ada artifact di proyek #$scId."
                            } else {
                                buildString {
                                    appendLine("=== Workspace Artifacts (#$scId · ${items.size} items) ===")
                                    items.forEachIndexed { idx, it ->
                                        appendLine("${idx + 1}. [${it.category}] ${it.title} (${it.badge}) -> ${it.file.absolutePath}")
                                    }
                                }.trim()
                            }
                        }
                        "write" -> {
                            if (fileName.isBlank()) return "Error: parameter 'file_name' wajib diisi untuk action='write'."
                            val saved = pro.sketchware.ai.ui.WorkspaceArtifactsManager.writeDeliverable(scId, fileName, content)
                            "Artifact '${saved.name}' berhasil disimpan (${saved.length()} bytes) di ${saved.absolutePath} dan tampil di panel Artifacts."
                        }
                        "read" -> {
                            if (fileName.isBlank()) return "Error: parameter 'file_name' wajib diisi untuk action='read'."
                            val direct = File(fileName)
                            val f = if (direct.isAbsolute && direct.isFile) {
                                direct
                            } else {
                                File(pro.sketchware.ai.ui.WorkspaceArtifactsManager.artifactsDir(scId), fileName)
                            }
                            if (!f.exists() || !f.isFile) {
                                return "Error: Artifact '$fileName' tidak ditemukan."
                            }
                            OutputSpiller.maybeSpill(scId, "artifact_${f.name}", f.readText())
                        }
                        "delete" -> {
                            val f = File(pro.sketchware.ai.ui.WorkspaceArtifactsManager.artifactsDir(scId), fileName)
                            if (f.exists() && f.delete()) {
                                "Artifact '${f.name}' berhasil dihapus."
                            } else {
                                "Error: Artifact '$fileName' tidak ditemukan di ai_artifacts."
                            }
                        }
                        else -> "Error: action '$action' tidak dikenal pada manage_artifacts."
                    }
                }

                "delegate_subagent" -> {
                    val role = args.str("role").ifBlank { "researcher" }
                    val task = args.str("task")
                    val targetScreens = args.str("target_screens")
                    if (task.isBlank()) return "Error: parameter 'task' wajib diisi untuk delegate_subagent."
                    onProgressUpdate?.invoke("Sub-Agent [$role] menganalisis tugas…")
                    val ctxBuilder = StringBuilder()
                    ctxBuilder.appendLine("=== Project Summary (#$scId) ===")
                    ctxBuilder.appendLine(ProjectGateway.getProjectSummary(scId))
                    if (targetScreens.isNotBlank()) {
                        targetScreens.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(4).forEach { scr ->
                            ctxBuilder.appendLine("\n=== Screen State: $scr ===")
                            ctxBuilder.appendLine(ProjectGateway.getScreenStateDetails(scId, scr))
                            ctxBuilder.appendLine("\n=== Layout XML: $scr ===")
                            ctxBuilder.appendLine(ProjectGateway.readScreenXml(context, scId, scr).take(4000))
                        }
                    }
                    val cfg = pro.sketchware.ai.config.AiConfigManager(context)
                    if (cfg.isConfigured()) {
                        try {
                            val subSystem = """You are a specialized DeerFlow Sub-Agent ($role) working inside Sketchware Pro for project #$scId.
Analyze the provided project context and answer the Lead Agent's task concisely with actionable findings, exact view/block/event IDs, and concrete recommendations."""
                            val subPrompt = "Task from Lead Agent:\n$task\n\nWorkspace Context:\n$ctxBuilder"
                            val resp = pro.sketchware.ai.provider.LlmClient(cfg).generateTurn(
                                systemPrompt = subSystem,
                                messages = listOf(pro.sketchware.ai.provider.LlmTurnMessage(role = "user", content = subPrompt)),
                                tools = emptyList()
                            )
                            val report = resp.text.ifBlank { "Sub-agent selesai memeriksa konteks." }
                            "=== Sub-Agent Report [$role] ===\n$report"
                        } catch (e: Throwable) {
                            "=== Sub-Agent Local Inspection [$role] (Fallback: ${e.message}) ===\n$ctxBuilder"
                        }
                    } else {
                        "=== Sub-Agent Local Inspection [$role] ===\n$ctxBuilder"
                    }
                }

                "reconstruct_apk_project" -> {
                    val action = args.str("action").ifBlank { "inspect" }.lowercase(Locale.US)
                    val apkPath = args.str("apk_path")
                    if (apkPath.isBlank()) {
                        return "Error: parameter 'apk_path' wajib diisi untuk reconstruct_apk_project."
                    }
                    when (action) {
                        "inspect", "info", "summary" -> {
                            onProgressUpdate?.invoke("REA: Menginspeksi struktur APK & DEX bytecode…")
                            val out = pro.sketchware.ai.project.ApkProjectReconstructor.inspectApk(context, scId, apkPath)
                            OutputSpiller.maybeSpill(scId, "reconstruct_apk_project", out)
                        }
                        "decode_xml", "xml", "manifest" -> {
                            val xmlEntry = args.str("xml_entry").ifBlank { "AndroidManifest.xml" }
                            onProgressUpdate?.invoke("REA: Mendekode binary XML '$xmlEntry'…")
                            val apkFile = pro.sketchware.ai.project.ApkProjectReconstructor.resolveApkFile(scId, apkPath)
                            if (!apkFile.isFile) {
                                "Error: File APK tidak ditemukan di '${apkFile.absolutePath}'."
                            } else {
                                val out = pro.sketchware.ai.project.ApkProjectReconstructor.decodeApkXmlEntry(context, apkFile, xmlEntry)
                                OutputSpiller.maybeSpill(scId, "decode_xml_${xmlEntry.substringAfterLast('/')}", out)
                            }
                        }
                        "analyze_dex", "dex", "classes" -> {
                            val query = args.str("query")
                            onProgressUpdate?.invoke("REA: Menganalisis tabel DEX (classes/methods/strings)…")
                            val apkFile = pro.sketchware.ai.project.ApkProjectReconstructor.resolveApkFile(scId, apkPath)
                            if (!apkFile.isFile) {
                                "Error: File APK tidak ditemukan di '${apkFile.absolutePath}'."
                            } else {
                                val report = pro.sketchware.ai.project.ApkProjectReconstructor.analyzeApkDex(
                                    apkFile = apkFile,
                                    appPackageHint = "",
                                    filterQuery = query,
                                    maxClasses = 80
                                )
                                val text = buildString {
                                    appendLine("=== 🔍 REA DEX ANALYSIS (${apkFile.name}) ===")
                                    appendLine("DEX Files: ${report.dexFilesCount} | Total Classes: ${report.totalClassesCount} | Methods: ${report.totalMethodsCount} | Fields: ${report.totalFieldsCount}")
                                    if (query.isNotEmpty()) appendLine("Filter Query: '$query'")
                                    appendLine("\n--- MATCHED CLASSES (${report.appClasses.size}) ---")
                                    for (cls in report.appClasses) {
                                        appendLine("• ${cls.className} (extends ${cls.superClass})${if (cls.sourceFile != null) " [${cls.sourceFile}]" else ""}")
                                        if (cls.fields.isNotEmpty()) appendLine("    Fields  : ${cls.fields.joinToString(", ")}")
                                        if (cls.methods.isNotEmpty()) appendLine("    Methods : ${cls.methods.joinToString(", ")}")
                                    }
                                    if (report.discoveredUrls.isNotEmpty()) {
                                        appendLine("\n--- DISCOVERED URLs (${report.discoveredUrls.size}) ---")
                                        report.discoveredUrls.forEach { appendLine("- $it") }
                                    }
                                    if (report.interestingStrings.isNotEmpty()) {
                                        appendLine("\n--- MATCHED / INTERESTING STRINGS (${report.interestingStrings.size}) ---")
                                        report.interestingStrings.forEach { appendLine("- $it") }
                                    }
                                }.trim()
                                OutputSpiller.maybeSpill(scId, "analyze_dex", text)
                            }
                        }
                        "full_clone", "clone", "reconstruct" -> {
                            if (! pro.sketchware.ai.config.AiConfigManager(context).autonomyMode.allowsWriteTools) {
                                return "Error: Action 'full_clone' mengubah proyek dan membutuhkan mode tulis (Copilot / Agent / Autopilot)."
                            }
                            val updateId = args.bool("update_app_identity", false)
                            val out = pro.sketchware.ai.project.ApkProjectReconstructor.fullCloneApkToProject(
                                context = context,
                                scId = scId,
                                rawApkPath = apkPath,
                                updateAppIdentity = updateId,
                                onProgress = onProgressUpdate
                            )
                            OutputSpiller.maybeSpill(scId, "reconstruct_apk_full_clone", out)
                        }
                        else -> "Error: action '$action' tidak dikenal pada reconstruct_apk_project. Gunakan: inspect, decode_xml, analyze_dex, atau full_clone."
                    }
                }

                else -> {
                    val resolvedMcp = McpClientHub.tryResolveQualifiedTool(toolName)
                    if (resolvedMcp != null) {
                        val (serverId, remoteTool) = resolvedMcp
                        onProgressUpdate?.invoke("Memanggil MCP [$serverId :: $remoteTool]…")
                        val out = McpClientHub.callRemoteTool(serverId, remoteTool, args)
                        OutputSpiller.maybeSpill(scId, toolName, out)
                    } else {
                        "Error: Tool '$toolName' tidak dikenali."
                    }
                }
            }
        } catch (t: Throwable) {
            "Tool Execution Error ($toolName): ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * Returns an error string when [command] matches a clearly destructive pattern and the caller
     * did not pass `confirm_destructive=true`; null when the command may proceed.
     */
    private fun guardDestructive(command: String, args: JsonObject): String? {
        if (args.bool("confirm_destructive", false)) return null
        val verdict = DangerousCommandGuard.inspect(command)
        if (!verdict.blocked) return null
        return "Error: perintah DIBLOKIR oleh guard keamanan (${verdict.reason}). " +
            "Perintah ini dapat menghapus data pengguna di luar workspace proyek (.sketchware) atau merusak sistem. " +
            "Jika target sebenarnya ada di dalam folder proyek, ubah path-nya. " +
            "Hanya jika pengguna secara eksplisit memintanya, ulangi dengan confirm_destructive=true."
    }

    private fun JsonObject.str(key: String): String {
        val el = get(key) ?: return ""
        return if (el.isJsonNull) "" else if (el.isJsonPrimitive) el.asString.trim() else el.toString()
    }

    private fun JsonObject.bool(key: String, default: Boolean): Boolean {
        val el = get(key) ?: return default
        if (el.isJsonNull) return default
        return try {
            if (el.asJsonPrimitive.isBoolean) el.asBoolean
            else el.asString.equals("true", ignoreCase = true)
        } catch (_: Exception) {
            default
        }
    }

    private fun JsonObject.long(key: String, default: Long): Long {
        val el = get(key) ?: return default
        if (el.isJsonNull) return default
        return try {
            if (el.asJsonPrimitive.isNumber) el.asLong
            else el.asString.trim().toLongOrNull() ?: default
        } catch (_: Exception) {
            default
        }
    }

    private fun defaultWorkspaceDir(scId: String): File {
        val ext = Environment.getExternalStorageDirectory()
        val mysc = File(ext, ".sketchware/mysc/$scId")
        if (mysc.isDirectory) return mysc
        val data = File(ext, ".sketchware/data/$scId")
        if (data.isDirectory) return data
        return LinuxSubsystem.baseDir()
    }

    private fun formatShellResult(res: ShellResult, scId: String): String {
        val sb = StringBuilder()
        sb.appendLine(
            "=== run_shell [${res.engine}] exit=${res.exitCode}" +
                (if (res.timedOut) " (TIMEOUT)" else "") + " in ${res.durationMs} ms ==="
        )
        if (res.stdout.isNotBlank()) {
            sb.appendLine("--- stdout ---")
            sb.appendLine(res.stdout)
        }
        if (res.stderr.isNotBlank()) {
            sb.appendLine("--- stderr ---")
            sb.appendLine(res.stderr)
        }
        if (res.stdout.isBlank() && res.stderr.isBlank()) {
            sb.appendLine("(tidak ada output)")
        }
        return OutputSpiller.maybeSpill(scId, "run_shell_${res.engine}", sb.toString().trim())
    }
}
