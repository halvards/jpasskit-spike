# jpasskit-spike

Java server to create Apple iOS Wallet passes (`*.pkpass`).

Start the server from your IDE by running the `Main` class.
 
Start the server on the default port (<http://localhost:4567>):

    ./gradlew run
    
Start the server on a different port:

    PORT=8080 ./gradlew run

Start the server on an arbitrary port:

    PORT=0 ./gradlew run

Create an executable all-in-one JAR file:

    ./gradlew onejar

Display the dependency tree for each configuration:

    ./gradlew dependencies

Display the available Gradle tasks:

    ./gradlew tasks
