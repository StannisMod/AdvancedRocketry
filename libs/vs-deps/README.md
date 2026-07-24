# Vendored VS runtime libraries (module-info stripped)

These are Valkyrien Skies' third-party runtime libraries (jackson 2.10.x + its
transitive `jackson-core`/`jackson-annotations`, and joml 1.9.25). They are
byte-for-byte the upstream Maven Central artifacts **with the single Java-9
`module-info.class` removed from the jar root**.

## Why
Forge 1.12.2's mod scanner runs on Java 8 with an old ASM that throws
`IllegalArgumentException` ("probably a corrupt zip") when it reads a Java-9
`module-info.class`, aborting mod loading. Stock VS avoided this by
shading+relocating these libraries into its own jar (which drops their
`module-info`); since we compile VS's source into AR and consume the libraries
directly, we strip `module-info.class` instead so the dev/test classpath boots.

`cqengine`, `javax.inject` and `picocli` carry no root `module-info.class`, so
they stay ordinary Maven `implementation` dependencies in
`gradle/scripts/dependencies.gradle`.

## How to regenerate
```bash
# for each artifact resolved from the Gradle cache:
python -c "import zipfile,os,glob
for p in glob.glob('libs/vs-deps/*.jar'):
    t=p+'.tmp'
    with zipfile.ZipFile(p) as i, zipfile.ZipFile(t,'w',zipfile.ZIP_DEFLATED) as o:
        [o.writestr(e, i.read(e.filename)) for e in i.infolist() if e.filename!='module-info.class']
    os.replace(t,p)"
```

Artifacts: jackson-databind, jackson-core, jackson-annotations,
jackson-dataformat-cbor, jackson-module-parameter-names, jackson-datatype-jdk8
(all 2.10.0) and joml 1.9.25.
