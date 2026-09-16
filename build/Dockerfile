# 使用本地固定JDK 21运行环境，避免构建阶段使用低版本glibc。
FROM openjdk:21-jdk AS build
COPY --from=maven:3.9.10-amazoncorretto-21 /usr/share/maven /usr/share/maven
COPY --from=maven:3.9.10-amazoncorretto-21 /usr/bin/mvn /usr/bin/mvn
ENV MAVEN_HOME=/usr/share/maven
ENV PATH="/usr/share/maven/bin:${PATH}"
WORKDIR /app/golden-eye
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
# 复制POM文件并下载依赖
COPY pom.xml .
RUN mvn dependency:go-offline -B
# 使用POM中固定的Playwright版本下载固定Chromium到共享目录；运行时不联网下载浏览器。
RUN mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.args="install chromium"
# 复制源代码并打包
COPY ./src src
RUN mvn clean package -DskipTests

# 运行环境由本地预构建的基础镜像提供：JDK21 + Chromium运行依赖 + 中文字体/emoji字体。
# 该基础镜像不是部署产物，只用于构建期复用；构建方式与升级触发条件见 build/Dockerfile.base。
# 改动基础镜像后必须同步修改此处的标签。
FROM golden-eye-env:1-ol8-jdk21

ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY --from=build /ms-playwright /ms-playwright

WORKDIR /app/golden-eye
# 从构建阶段复制生成的jar文件
COPY --from=build /app/golden-eye/target/*.jar app.jar
# 设置JVM参数
ENV JAVA_TOOL_OPTIONS="-Xmx2048m -Xms512m"
# 设置时区（解决容器内时间问题）
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
# 暴露端口，暂时没有端口可以不用暴露
# EXPOSE 8080
RUN groupadd --gid 10001 golden-eye \
    && useradd --uid 10001 --gid 10001 --shell /sbin/nologin --no-create-home golden-eye \
    && chown -R golden-eye:golden-eye /app/golden-eye /ms-playwright \
    && chmod 1777 /tmp
# 启动应用
USER golden-eye
ENTRYPOINT ["java", "-jar", "app.jar"]
