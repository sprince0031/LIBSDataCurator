LIBS Data Curator - Distribution Package
=======================================

This package contains the LIBS Data Curator application for automated collection
of Laser-Induced Breakdown Spectroscopy (LIBS) data from the NIST LIBS database.

RUNNING THE APPLICATION
======================

To run the application, use:

    java -jar lib/LIBSDataCurator.jar [options]

EXAMPLES
========

1. Generate data for a composition with variations:
   java -jar lib/LIBSDataCurator.jar -c "Fe-80,C-20" -v -n 5

2. Process a steel series:
   java -jar lib/LIBSDataCurator.jar -s aisi.10xx -v -n 10

3. Show help:
   java -jar lib/LIBSDataCurator.jar

REQUIREMENTS
============

- Java 21 or higher
- Network access to physics.nist.gov for data retrieval

DIRECTORIES
===========

- lib/     - Contains the application JAR file
- conf/    - Configuration files
- data/    - Output data storage (created automatically)
- logs/    - Application logs (created automatically)
- docs/    - Documentation

For more information, see the documentation in the docs/ directory.