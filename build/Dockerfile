# 使用OpenJDK的Maven构建环境
FROM maven:3.9.10-amazoncorretto-21 AS build
WORKDIR /app/golden-eye
# 复制POM文件并下载依赖
COPY pom.xml .
RUN mvn dependency:go-offline -B
# 复制源代码并打包
COPY ./src src
RUN mvn clean package -DskipTests

#使用轻量级OpenJDK运行环境
FROM openjdk:21-jdk-slim
WORKDIR /app/golden-eye
# 从构建阶段复制生成的jar文件
COPY --from=build /app/golden-eye/target/*.jar app.jar
# 设置JVM参数
ENV JAVA_TOOL_OPTIONS="-Xmx512m -Xms256m"
# 设置时区（解决容器内时间问题）
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
# 暴露端口，暂时没有端口可以不用暴露
# EXPOSE 8080
# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]