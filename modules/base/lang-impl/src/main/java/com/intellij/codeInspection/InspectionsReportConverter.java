package com.intellij.codeInspection;

import com.intellij.codeInspection.ex.Tools;
import com.intellij.openapi.extensions.ExtensionPointName;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * @author Roman.Chernyatchik
 */
public interface InspectionsReportConverter {
  ExtensionPointName<InspectionsReportConverter> EP_NAME = ExtensionPointName.create("com.intellij.inspectionsReportConverter");

  /**
   * @return Format name which will be specified by user using --format option
   */
  String getFormatName();

  /**
   * @return Try if original xml base report isn't required to user and should be stored in tmp directory.
   */
  boolean useTmpDirForRawData();

  /**
   * @param rawDataDirectoryPath Original XML report folder
   * @param outputPath New report output path provided by user. If null use STDOUT.
   * @param tools Inspections data
   * @param inspectionsResults Files with inspection results
   */
  void convert(@Nonnull String rawDataDirectoryPath,
               @Nullable String outputPath,
               @Nonnull Map<String, Tools> tools,
               @Nonnull List<File> inspectionsResults) throws ConversionException;

  class ConversionException extends Exception {
    public ConversionException(String message) {
      super(message);
    }
  }
}
