# Class Label Type Selection Feature

## Overview

The LIBS Data Curator now supports different class label types for machine learning dataset generation. This feature allows users to choose the type of class labels added to the generated CSV dataset, enabling different machine learning approaches.

## Available Class Label Types

### 1. Composition Percentages (Default)
- **Option**: `--class-type 1` or omit the option
- **Use case**: Multi-output regression with element weight percentages
- **Column name**: `class_composition_percentage`
- **Example values**: `Fe-80.0;C-20.0`, `Fe-75.5;C-18.2;Mn-6.3`
- **Description**: Uses the composition string as the class label, suitable for predicting element percentages

### 2. Material Grade Name
- **Option**: `--class-type 2`
- **Use case**: Multi-class classification with specific material grades
- **Column name**: `material_grade_name`
- **Example values**: `AISI 1018 Steel`, `AISI 4140 Steel`, `AISI 316 Stainless Steel`
- **Description**: Uses the material name scraped from MatWeb datasheets for classifying specific steel grades

### 3. Material Type
- **Option**: `--class-type 3`
- **Use case**: Multi-class classification with broader material categories
- **Column name**: `material_type`
- **Example values**: `AISI 1000 Series Carbon Steel`, `AISI 4000 Series Chromium-Molybdenum Steel`, `300 Series Austenitic Stainless Steel`
- **Description**: Uses the series category derived from the materials catalog for classifying broader steel types

## Usage Examples

### Basic Composition (Default)
```bash
java -jar LIBSDataCurator.jar -c "Fe-80,C-20"
# Generates dataset with composition percentages as class labels
```

### Steel Grade Classification
```bash
java -jar LIBSDataCurator.jar -c "some_matweb_guid" --class-type 2
# Generates dataset with steel grade names as class labels
```

### Steel Type Classification  
```bash
java -jar LIBSDataCurator.jar -s aisi.10xx.series --class-type 3
# Generates dataset with steel type categories as class labels
```

### With Compositional Variations
```bash
java -jar LIBSDataCurator.jar -s aisi.10xx.series -v -n 50 --class-type 2
# Generates 50 compositional variations per material with steel grade names
```

## Output Format

The generated `master_dataset.csv` will include:
- Composition column
- Wavelength intensity columns  
- Element percentage columns
- **New**: Class label column (name depends on selected type)

## Implementation Details

### Fallback Behavior
- **Steel grade mode**: If material name is unavailable, uses "Unknown Grade"
- **Steel type mode**: If series information is unavailable, uses "Unknown Type"
- **Invalid options**: Default to composition percentage mode

### Series Key Mapping
The application automatically maps series keys to readable steel types:
- `aisi.10xx.series` → "aisi 10xx series"
- `aisi.41xx.series` → "aisi 41xx series"
- `t.30x.series` → "t 30x series"
- `astm.structural.series` → "astm structural series"
- Custom parsing for other series formats

## Machine Learning Use Cases

1. **Multi-output Regression** (Type 1): Predict element percentages from spectral data
2. **Multi-class Classification** (Types 2 & 3): Classify materials into predefined categories
3. **Transfer Learning**: Train on broad categories (Type 3) and fine-tune on specific grades (Type 2)

## Backward Compatibility

- Existing command-line usage remains unchanged
- Default behavior (composition percentages) is preserved
- No breaking changes to existing datasets