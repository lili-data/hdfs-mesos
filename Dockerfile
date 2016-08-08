FROM mesosphere/mesos:1.0.0

MAINTAINER liligo data team <datateam@liligo.com>

RUN apt-get -y update
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y --force-yes software-properties-common python-software-properties curl
RUN apt-add-repository -y ppa:webupd8team/java
RUN apt-get -y update
RUN /bin/echo debconf shared/accepted-oracle-license-v1-1 select true | /usr/bin/debconf-set-selections
RUN DEBIAN_FRONTEND=noninteractive apt-get -y install oracle-java8-installer oracle-java8-set-default

ENV JAVA_HOME /usr/lib/jvm/java-8-oracle/

WORKDIR /tmp

RUN git clone https://github.com/tromika/hdfs-mesos.git && \
                    cd hdfs-mesos && \
                    ./gradlew jar

WORKDIR /tmp/hdfs-mesos

RUN wget -q https://archive.apache.org/dist/hadoop/core/hadoop-2.7.2/hadoop-2.7.2.tar.gz
