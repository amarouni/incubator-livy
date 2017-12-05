FROM registry.datapwn.com/talend/spark:1.6.2-talend-5
MAINTAINER amarouni@talend.com

ARG LIVY_VERSION
ENV SPARK_VERSION 1.6.2-talend-5
ENV SPARK_HOME /opt/spark
ENV LIVY_PORT 8998

ADD target/livy-"${LIVY_VERSION}"-bin.tar.gz /opt
RUN mkdir /opt/livy-"${LIVY_VERSION}"-bin/logs
ADD min_livy.conf /opt/livy-"${LIVY_VERSION}"-bin/conf/livy.conf

EXPOSE $LIVY_PORT
CMD /opt/livy-"${LIVY_VERSION}"-bin/bin/livy-server

HEALTHCHECK --interval=5s --timeout=3s --retries=3 \
 CMD curl -f http://localhost:${LIVY_PORT} || exit 1
