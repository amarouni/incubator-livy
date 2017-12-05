# Livy Docker image
dockerfile-maven plugin in assembly module is used to build the docker image.
The docker image build is included in the package phase so running the following on the parent pom will build the image 
```
mvn -U clean package -DskipITs -DskipTests -P targz
```
Start Livy container  :
```
docker run -p 8998:8998 -d talend/livy:0.5.0-SNAPSHOT
```
Run a smoke test :
```
curl -X POST --data '{"file": "/opt/spark/examples/spark-pi-example.jar", "className": "org.apache.spark.examples.SparkPi"}' -H "Content-Type: application/json" localhost:8998/batches
```
Then go to http://localhost:8998/

# Build Spark base image
Talend Spark base image based on java8 alpine image with our own Spark 

Get our custom spark : https://artifacts-zl.talend.com/nexus/content/repositories/thirdparty-releases/org/apache/spark/spark-distrib/1.6.2-talend-5/spark-distrib-1.6.2-talend-5.tgz

Unzip the tar and build the docker image using the `sparkbase_Dockerfile

# TODO
- Automate base image build (spark base image)
- Add a dependencies layer

