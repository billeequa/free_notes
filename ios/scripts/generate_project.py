#!/usr/bin/env python3
"""Generate a complete, deterministic Xcode project with no Ruby/Homebrew dependency."""
import hashlib, json, pathlib
root = pathlib.Path(__file__).resolve().parents[1]
def uid(name): return hashlib.sha256(name.encode()).hexdigest()[:24].upper()
def quote(value): return json.dumps(str(value))
objects = {}
def add(key, body): objects[uid(key)] = body; return uid(key)
def ref(key): return uid(key)
source_paths = sorted(str(p.relative_to(root)) for p in (root/'JNotes').rglob('*.swift'))
source_paths += ['Vendor/argon2/'+p for p in ['src/argon2.c','src/core.c','src/encoding.c','src/ref.c','src/thread.c','src/blake2/blake2b.c']]
test_paths = sorted(str(p.relative_to(root)) for p in (root/'JNotesTests').glob('*.swift'))
resource_paths = ['JNotes/Resources/jnotes-service.json','JNotes/Resources/Assets.xcassets','JNotes/PrivacyInfo.xcprivacy']
test_resources = ['JNotesTests/vectors.json']
all_paths = source_paths + test_paths + resource_paths + test_resources + ['JNotes/Info.plist','JNotes/JNotes-Bridging-Header.h']
files=[]
for path in all_paths:
    kind = 'sourcecode.swift' if path.endswith('.swift') else 'sourcecode.c.c' if path.endswith('.c') else 'folder.assetcatalog' if path.endswith('.xcassets') else 'sourcecode.c.h' if path.endswith('.h') else 'text.plist.xml' if path.endswith(('.plist','.xcprivacy')) else 'text.json'
    files.append(add('file:'+path, f'isa = PBXFileReference; lastKnownFileType = {quote(kind)}; path = {quote(path)}; sourceTree = "<group>";'))
    add('build:'+path, f'isa = PBXBuildFile; fileRef = {ref("file:"+path)};')
product=add('product','isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = JNotes.app; sourceTree = BUILT_PRODUCTS_DIR;')
test_product=add('test-product','isa = PBXFileReference; explicitFileType = wrapper.cfbundle; includeInIndex = 0; path = JNotesTests.xctest; sourceTree = BUILT_PRODUCTS_DIR;')
products=add('products',f'isa = PBXGroup; children = ({product}, {test_product}); name = Products; sourceTree = "<group>";')
add('root', f'isa = PBXGroup; children = ({", ".join(files+[products])}); sourceTree = "<group>";')
for name, paths, isa in [('sources',source_paths,'PBXSourcesBuildPhase'),('resources',resource_paths,'PBXResourcesBuildPhase'),('test-sources',test_paths,'PBXSourcesBuildPhase'),('test-resources',test_resources,'PBXResourcesBuildPhase')]:
    add(name,f'isa = {isa}; buildActionMask = 2147483647; files = ({", ".join(ref("build:"+p) for p in paths)}); runOnlyForDeploymentPostprocessing = 0;')
package=add('package','isa = XCRemoteSwiftPackageReference; repositoryURL = "https://github.com/weichsel/ZIPFoundation.git"; requirement = {kind = exactVersion; version = 0.9.19;};')
dep=add('package-product',f'isa = XCSwiftPackageProductDependency; package = {package}; productName = ZIPFoundation;')
build_dep=add('package-build',f'isa = PBXBuildFile; productRef = {dep};')
add('frameworks', f'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = ({build_dep}); runOnlyForDeploymentPostprocessing = 0;')
add('test-frameworks','isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
proxy=add('test-proxy',f'isa = PBXContainerItemProxy; containerPortal = {ref("project")}; proxyType = 1; remoteGlobalIDString = {ref("target")}; remoteInfo = JNotes;')
target_dep=add('test-target-dependency',f'isa = PBXTargetDependency; target = {ref("target")}; targetProxy = {proxy};')
add('target', f'isa = PBXNativeTarget; buildConfigurationList = {ref("app-configs")}; buildPhases = ({ref("sources")}, {ref("frameworks")}, {ref("resources")}); buildRules = (); dependencies = (); name = JNotes; packageProductDependencies = ({dep}); productName = JNotes; productReference = {product}; productType = "com.apple.product-type.application";')
add('test-target',f'isa = PBXNativeTarget; buildConfigurationList = {ref("test-configs")}; buildPhases = ({ref("test-sources")}, {ref("test-frameworks")}, {ref("test-resources")}); buildRules = (); dependencies = ({target_dep}); name = JNotesTests; productName = JNotesTests; productReference = {test_product}; productType = "com.apple.product-type.bundle.unit-test";')
for mode in ['Debug','Release']:
    common={'CLANG_ENABLE_MODULES':'YES','CLANG_ENABLE_OBJC_ARC':'YES','SDKROOT':'iphoneos','IPHONEOS_DEPLOYMENT_TARGET':'17.0','SWIFT_VERSION':'5.0','TARGETED_DEVICE_FAMILY':'1,2','DEBUG_INFORMATION_FORMAT':'dwarf' if mode=='Debug' else 'dwarf-with-dsym','ENABLE_TESTABILITY':'YES' if mode=='Debug' else 'NO','GCC_C_LANGUAGE_STANDARD':'gnu11','SWIFT_OPTIMIZATION_LEVEL':'-Onone' if mode=='Debug' else '-O','GCC_OPTIMIZATION_LEVEL':'0' if mode=='Debug' else 's','SWIFT_ACTIVE_COMPILATION_CONDITIONS':'DEBUG $(inherited)' if mode=='Debug' else '$(inherited)','SWIFT_STRICT_CONCURRENCY':'targeted'}
    app={'PRODUCT_NAME':'JNotes','PRODUCT_BUNDLE_IDENTIFIER':'com.billeequa.jnotes.ios','INFOPLIST_FILE':'JNotes/Info.plist','GENERATE_INFOPLIST_FILE':'NO','CODE_SIGN_STYLE':'Automatic','DEVELOPMENT_TEAM':'','MARKETING_VERSION':'1.0.0','CURRENT_PROJECT_VERSION':'1','SWIFT_OBJC_BRIDGING_HEADER':'JNotes/JNotes-Bridging-Header.h','HEADER_SEARCH_PATHS':'$(SRCROOT)/Vendor/argon2/include $(SRCROOT)/Vendor/argon2/src','GCC_PREPROCESSOR_DEFINITIONS':'$(inherited) ARGON2_NO_THREADS=1','ASSETCATALOG_COMPILER_APPICON_NAME':'AppIcon','LD_RUNPATH_SEARCH_PATHS':'$(inherited) @executable_path/Frameworks','SUPPORTED_PLATFORMS':'iphoneos iphonesimulator','SUPPORTS_MACCATALYST':'NO'}
    test={'PRODUCT_NAME':'JNotesTests','PRODUCT_BUNDLE_IDENTIFIER':'com.billeequa.jnotes.ios.tests','GENERATE_INFOPLIST_FILE':'YES','CODE_SIGN_STYLE':'Automatic','DEVELOPMENT_TEAM':'','TEST_HOST':'$(BUILT_PRODUCTS_DIR)/JNotes.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/JNotes','BUNDLE_LOADER':'$(TEST_HOST)','LD_RUNPATH_SEARCH_PATHS':'$(inherited) @executable_path/Frameworks @loader_path/Frameworks','HEADER_SEARCH_PATHS':'$(SRCROOT)/Vendor/argon2/include','SWIFT_OBJC_BRIDGING_HEADER':'JNotes/JNotes-Bridging-Header.h'}
    for name, settings in [('project',common),('app',app),('test',test)]:
        values=' '.join(f'{k} = {quote(v)};' for k,v in sorted(settings.items()))
        add(name+'-'+mode, f'isa = XCBuildConfiguration; buildSettings = {{{values}}}; name = {mode};')
for name in ['project','app','test']:
    add(name+'-configs',f'isa = XCConfigurationList; buildConfigurations = ({ref(name+"-Debug")}, {ref(name+"-Release")}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
add('project',f'isa = PBXProject; attributes = {{BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 1600; TargetAttributes = {{{ref("target")} = {{CreatedOnToolsVersion = 16.0;}}; {ref("test-target")} = {{CreatedOnToolsVersion = 16.0; TestTargetID = {ref("target")};}};}};}}; buildConfigurationList = {ref("project-configs")}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base); mainGroup = {ref("root")}; productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; packageReferences = ({package}); targets = ({ref("target")}, {ref("test-target")});')
project=root/'JNotes.xcodeproj'; project.mkdir(exist_ok=True)
text='// !$*UTF8*$!\n{\n archiveVersion = 1;\n classes = {};\n objectVersion = 56;\n objects = {\n'+''.join(f'  {key} = {{{value}}};\n' for key,value in sorted(objects.items()))+f' }};\n rootObject = {ref("project")};\n}}\n'
(project/'project.pbxproj').write_text(text)
scheme=project/'xcshareddata/xcschemes'; scheme.mkdir(parents=True,exist_ok=True)
app_ref=f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{ref("target")}" BuildableName="JNotes.app" BlueprintName="JNotes" ReferencedContainer="container:JNotes.xcodeproj"/>'
test_ref=f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{ref("test-target")}" BuildableName="JNotesTests.xctest" BlueprintName="JNotesTests" ReferencedContainer="container:JNotes.xcodeproj"/>'
(scheme/'JNotes.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{app_ref}</BuildActionEntry></BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO">{test_ref}</TestableReference></Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app_ref}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>''')
lock=project/'project.xcworkspace/xcshareddata/swiftpm'; lock.mkdir(parents=True,exist_ok=True)
(lock/'Package.resolved').write_text(json.dumps({'pins':[{'identity':'zipfoundation','kind':'remoteSourceControl','location':'https://github.com/weichsel/ZIPFoundation.git','state':{'revision':'02b6abe5f6eef7e3cbd5f247c5cc24e246efcfe0','version':'0.9.19'}}],'version':2},indent=2)+'\n')
workspace=project/'project.xcworkspace/contents.xcworkspacedata';workspace.write_text('<?xml version="1.0" encoding="UTF-8"?><Workspace version="1.0"><FileRef location="self:"/></Workspace>\n')
print(f'Generated {project.name}: {len(source_paths)} app sources, {len(test_paths)} test sources')
