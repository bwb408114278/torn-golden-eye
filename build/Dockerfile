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

#使用OpenJDK运行环境
FROM openjdk:21-jdk

# Chromium运行依赖固定在Oracle Linux 8镜像内，不依赖宿主机或NAS环境。
RUN microdnf install -y \
    ca-certificates \
    fontconfig \
    alsa-lib \
    atk \
    at-spi2-atk \
    at-spi2-core \
    cairo \
    cups-libs \
    dbus-libs \
    libdrm \
    mesa-libEGL \
    mesa-libgbm \
    glib2 \
    gtk3 \
    nspr \
    nss \
    pango \
    libX11 \
    libXcomposite \
    libXdamage \
    libXext \
    libXfixes \
    libXi \
    libXrandr \
    libxcb \
    libxkbcommon \
    libxshmfence \
    liberation-fonts \
    google-noto-emoji-fonts \
    && microdnf clean all

ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY --from=build /ms-playwright /ms-playwright

# 创建字体目录
RUN mkdir -p /usr/share/fonts/custom
# 将宿主机的Fonts目录下的所有字体文件复制到容器的字体目录
COPY ./build/fonts/msyh.ttc /usr/share/fonts/custom/msyh.ttc
COPY ./build/fonts/msyhl.ttc /usr/share/fonts/custom/msyhl.ttc
COPY ./build/fonts/msyhbd.ttc /usr/share/fonts/custom/msyhbd.ttc
# 刷新字体缓存，确保系统识别新添加的字体
RUN fc-cache -f -v
# Oracle Linux google-noto-emoji-fonts 的版权与许可证说明归档到可审计路径。
RUN mkdir -p /usr/share/licenses/golden-eye \
    && cp /usr/share/licenses/google-noto-emoji-fonts/LICENSE \
        /usr/share/licenses/golden-eye/LICENSE-google-noto-emoji-fonts \
    && test -s /usr/share/licenses/golden-eye/LICENSE-google-noto-emoji-fonts
COPY ./src/main/resources/fonts/LICENSE-google-noto-emoji-fonts.txt \
    /usr/share/licenses/golden-eye/LICENSE-google-noto-emoji-fonts.txt

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
