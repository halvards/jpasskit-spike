# jpasskit-spike

Java server to create [Apple iOS Wallet](https://developer.apple.com/library/ios/documentation/UserExperience/Conceptual/PassKit_PG/index.html) passes (`*.pkpass`) using [jPasskit](https://github.com/drallgood/jpasskit).

The generated passes currently don't support [localisation](https://developer.apple.com/library/ios/documentation/UserExperience/Conceptual/PassKit_PG/Creating.html).

## Prerequisites

- [Java SE Development Kit 8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html)
- [Apple Developer](https://developer.apple.com/programs/) account to create a Pass Type ID and generate a certificate and keypair for signing passes

## Environment variables

Set the following environment variables for running the server:

- `PASS_TYPE_IDENTIFIER`: Use the same value supplied when setting up your Pass Type ID. It should start with `pass.`, e.g. `pass.com.apple.devpubs.example`
- `PORT`: Optional, defaults to Spark default 4567.
- `PRIVATE_KEY_P12_BASE64`: The Base64 encoded contents of a PKCS #12 file containing your Pass Type ID private key and certificate. See below for instructions on how to obtain this. 
- `PRIVATE_KEY_PASSPHRASE`: The passphrase required to access the contents of the PKCS #12 file containing your Pass Type ID private key and certificate. 
- `TEAM_IDENTIFIER`: Your iOS developer account team identifier, e.g., `A93A5CM278`
- `WEB_SERVICE_URL`: Optional, your PassKit Web Service URL, e.g., `https://example.com/passes/`. See <https://developer.apple.com/library/ios/documentation/PassKit/Reference/PassKit_WebService/WebService.html> for more information.

## PRIVATE_KEY_P12_BASE64 and PRIVATE_KEY_PASSPHRASE

To obtain this value, locate the private key of your Pass Type ID certificate in Keychain Access. Right-click and select 'Export' to save it as a `.p12` file.

The password you supply should be set as the `PRIVATE_KEY_PASSPHRASE` environment variable.
 
Run this command to extract the value to set as the `PRIVATE_KEY_P12_BASE64` environment variable:
  
    base64 -i Certificates.p12 -o -
    
On Mac OS you can optionally pipe this to `pbcopy` to add the result to your clipboard.

## Run the application

Start the server from your IDE by running the `Main` class. Set environment variables in the IDE run configuration.
 
Start the server from a terminal on the default port (<http://localhost:4567>):

    ./gradlew run
    
Start the server on a different port:

    PORT=8080 ./gradlew run

Start the server on an arbitrary port:

    PORT=0 ./gradlew run

## Other build tasks

Create an executable all-in-one JAR file:

    ./gradlew onejar

Display the dependency tree for each configuration:

    ./gradlew dependencies

Display the available Gradle tasks:

    ./gradlew tasks
