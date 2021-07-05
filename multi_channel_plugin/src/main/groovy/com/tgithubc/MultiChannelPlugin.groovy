package com.tgithubc

import com.android.build.gradle.AppPlugin
import com.android.builder.model.SigningConfig
import org.apache.commons.io.FilenameUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.plugins.ExtensionAware
import org.gradle.internal.reflect.Instantiator

import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes


class MultiChannelPlugin implements Plugin<Project> {

    def CHANNEL_DIR = "/assets"
    def CHANNEL_FILE = "/assets/channel_info"
    def JARSIGNER_EXE = ".." + File.separator + "bin" + File.separator + "jarsigner"
    def ZIPALIGN_EXE = "zipalign"
    def jarsignerExe
    def zipalignExe

    @Override
    void apply(Project project) {
        project.extensions.create("multichannel", MultiChannelPluginExtension)//注册扩展 MultiChannelPluginExtension
        //注册扩展 channelConfig
        project.multichannel.extensions.channelConfig = project.container(ChannelExtension) { String name ->
            ChannelExtension channelExtension = project.gradle.services.get(Instantiator).newInstance(ChannelExtension, name)
            assert channelExtension instanceof ExtensionAware
            return channelExtension
        }

        project.afterEvaluate {//project对应的gradle文件解析完毕后调用
            def hasApp = project.plugins.withType(AppPlugin)
            if (!hasApp) {
                return
            }

            final def log = project.logger
            final def variants = project.android.applicationVariants //所有打包变体是个Collection

            if (project.multichannel.jarsignerPath) {//配置签名工具路径
                jarsignerExe = project.multichannel.jarsignerPath
            } else {//使用默认路径
                jarsignerExe = System.properties.'java.home' + File.separator + JARSIGNER_EXE
            }

            if (project.multichannel.zipalignPath) {//配置zip对齐工具路径
                zipalignExe = project.multichannel.zipalignPath
            } else {//使用默认路径
                zipalignExe = "${project.android.getSdkDirectory().getAbsolutePath()}" + File.separator + "build-tools" + File.separator + project.android.buildToolsVersion + File.separator + ZIPALIGN_EXE
            }

            log.debug("jarsignerExe: " + jarsignerExe)
            log.debug("zipalignExe: " + zipalignExe)

            //循环所有变体(productFlavors)，
            variants.all { variant ->
                def flavorName = variant.properties.get('flavorName')//android.productFlavors对应的名称

                variant.assemble.doLast {//变体的 assemble 任务执行完毕后包已经打好并且签名完毕
                    def defaultSignConfig = project.multichannel.defaultSigningConfig

                    //
                    project.multichannel.channelConfig.each() { config ->
                        if (flavorName.equals(config.name)) {
                            log.debug("Generate channel based on $config.name")

                            def signConfig = (config.signingConfig != null && config.signingConfig.isSigningReady()) ? config.signingConfig : defaultSignConfig

                            if (signConfig == null || !signConfig.isSigningReady()) {
                                throw new ProjectConfigurationException("Could not resolve signing config.", null)
                            }

                            config.childFlavors.each() { childFlavor ->
                                log.debug("\tNew channel: $childFlavor")
                                Path path = Paths.get(variant.getOutputs().first().getOutputFile().getAbsolutePath())

                                genApkWithChannel(project,
                                        path.getParent().toString() + File.separator,
                                        FilenameUtils.removeExtension(path.getFileName().toString()),
                                        childFlavor,
                                        signConfig
                                )

                            }
                            // delete pkg
                            variant.getOutputs().first().getOutputFile().delete()
                        }
                    }
                }
            }

            project.task('displayChannelConfig').doLast {
                project.multichannel.channelConfig.each() { config ->
                    def defaultSignConfig = project.multichannel.defaultSigningConfig
                    def signConfig = (config.signingConfig != null && config.signingConfig.isSigningReady()) ? config.signingConfig : defaultSignConfig

                    println "\\-----$config.name"
                    println "\t\\-----signConfig: ${signConfig.getName()}"
                    config.childFlavors.each() { childFlavor ->
                        println "\t\\-----$childFlavor"
                    }
                }
            }
        }
    }

    //解压生成的签名apk，然后读取channel注入到 apk 的asset目录下，之后再重新签名
    void genApkWithChannel(Project project, String apkPath, String apkName, String channel, SigningConfig signConfig) throws IOException, InterruptedException {
        def log = project.logger

        log.debug("genApkWithChannel: " +
                "\n\t" + apkPath +
                "\n\t" + apkName +
                "\n\t" + channel +
                "\n\t" + project.multichannel.prefix +
                "\n\t" + project.multichannel.subfix +
                "\n\t" + signConfig.getStoreFile().getAbsolutePath() +
                "\n\t" + signConfig.getKeyAlias() +
                "\n\t" + signConfig.getStorePassword() +
                "\n\t" + signConfig.getKeyPassword())

        // create temp file: xx.apk --> xx.zip
        File oldFile = new File(apkPath + apkName + ".apk")
        File tempFile = new File(apkPath + apkName + "_" + channel + "_temp.zip")

        File tempApkFile = new File(apkPath + apkName + "_" + channel + "_temp.apk")

        // outFile is the final output apk file
        File outFile = new File(apkPath + project.multichannel.prefix + channel + project.multichannel.subfix + ".apk")

        if (outFile.exists()) {
            outFile.delete()
        }

        // copy oldFile to tempFile
        Files.copy(oldFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        FileSystem zipFileSystem = createZipFileSystem(tempFile.getAbsolutePath(), false)

        // delete META-INF
        deleteEntry(zipFileSystem, "/META-INF/")

        createEntry(zipFileSystem, CHANNEL_FILE, channel)

        zipFileSystem.close()

        // rename file: xx.zip --> xx_temp.apk and waiting for re-sign
        Files.move(tempFile.toPath(), tempApkFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        // re-sign apk
        String signCmd = (jarsignerExe + " -verbose -sigalg SHA1withRSA -digestalg SHA1 -keystore " + signConfig.getStoreFile().getAbsolutePath() + " -storepass " + signConfig.getStorePassword() + " -keypass " + signConfig.getKeyPassword() + " " + tempApkFile.getAbsolutePath().replaceAll(" ", "\" \"") + " " + signConfig.getKeyAlias())

        if (execCmdAndWait(signCmd, true) == 0) {
            log.debug("jarsigner process: " + tempApkFile.getAbsolutePath())
        } else {
            log.error("jarsigner Error: " + tempApkFile.getAbsolutePath())
            return
        }

        // zipalign
        String zipAlignCmd = (zipalignExe + " -v 4 " + tempApkFile.getAbsolutePath().replaceAll(" ", "\" \"") + " " + outFile.getAbsolutePath().replaceAll(" ", "\" \""))

        if (execCmdAndWait(zipAlignCmd, true) == 0) {
            log.debug("zipalign process: " + tempApkFile.getAbsolutePath())
        } else {
            log.error("zipalign Error: " + tempApkFile.getAbsolutePath())
            return
        }
        // delete temp apk file
        tempApkFile.delete()
    }

    void createEntry(FileSystem zipFileSystem, String entryName, String content) throws IOException {
        Path nf = zipFileSystem.getPath(entryName)

        Path dirPath = zipFileSystem.getPath(CHANNEL_DIR)

        if (!Files.exists(dirPath)) {
            Files.createDirectory(dirPath)
        }
        Writer writer = Files.newBufferedWriter(nf, StandardCharsets.UTF_8, StandardOpenOption.CREATE)
        writer.write(content + "-" + System.currentTimeMillis())
        writer.flush()
        writer.close()
    }

    static void deleteEntry(FileSystem zipFileSystem, String entryName) throws IOException {
        Path path = zipFileSystem.getPath(entryName)
        if (!Files.exists(path)) {
            return
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {

                println("Deleting file: " + file)
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                println("Deleting dir: " + dir)
                if (exc == null) {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                } else {
                    throw exc
                }
            }
        })
    }

    static FileSystem createZipFileSystem(String zipFilename, boolean create) throws IOException {
        // convert the filename to a URI
        final Path path = Paths.get(zipFilename)
        final URI uri = URI.create("jar:file:" + path.toUri().getPath())

        final Map<String, String> env = new HashMap<String, String>()
        if (create) {
            env.put("create", "true")
        }
        return FileSystems.newFileSystem(uri, env)
    }

    static int execCmdAndWait(String cmd, boolean showOutput) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(cmd)
        if (showOutput) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))
            String line
            while ((line = reader.readLine()) != null) {
                println("jarsigner output: " + line)
            }
        }
        return process.waitFor()
    }
}
