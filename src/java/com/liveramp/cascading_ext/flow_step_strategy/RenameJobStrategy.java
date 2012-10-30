package com.liveramp.cascading_ext.flow_step_strategy;

import cascading.flow.Flow;
import cascading.flow.FlowStep;
import cascading.flow.FlowStepStrategy;
import cascading.tap.MultiSourceTap;
import cascading.tap.Tap;
import cascading.tap.hadoop.Hfs;
import com.google.common.base.Joiner;
import com.liveramp.cascading_ext.tap.NullTap;
import com.twitter.maple.tap.MemorySourceTap;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RenameJobStrategy implements FlowStepStrategy<JobConf> {

  private static final int MAX_JOB_NAME_LENGTH = 175;
  private static final int MAX_SOURCE_PATH_NAMES_LENGTH = 60;
  private static final Pattern TEMP_PIPE_NAME = Pattern.compile("^(.*?)_\\d+_[A-Z0-9]{32}$");


  @Override
  public void apply(Flow<JobConf> flow, List<FlowStep<JobConf>> predecessorSteps, FlowStep<JobConf> flowStep) {
    //  Give jobs human readable names. The default naming scheme includes a bunch of randomly generated IDs.
    flowStep.getConfig().setJobName(formatJobName(flowStep));
  }

  private String formatJobName(FlowStep<JobConf> flowStep) {
    // WordCount [(2/5) input_1, input_2] -> output_1232_ABCDEF123456789...

    String jobName = String.format(
        "%s [(%d/%d) %s] -> %s",
        flowStep.getFlowName(),
        flowStep.getStepNum(),
        flowStep.getFlow().getFlowSteps().size(),
        StringUtils.abbreviate(
            join(getPathNamesFromTaps(flowStep.getSources(), true)),
            MAX_SOURCE_PATH_NAMES_LENGTH),
        join(getPathNamesFromTaps(flowStep.getSinks(), false)));

    return StringUtils.abbreviate(jobName, MAX_JOB_NAME_LENGTH);
  }

  protected List<String> getPathNamesFromTaps(Set<Tap> taps, boolean removeRandomSuffixFromTempTaps) {
    List<String> pathNames = new ArrayList<String>();

    for (Tap tap : taps) {
      pathNames.add(getTapIdentifier(tap, removeRandomSuffixFromTempTaps));
    }

    return pathNames;
  }

  private static String join(List<String> strings) {
    return Joiner.on(", ").join(strings);
  }

  private static String getCanonicalName(String name) {
    Matcher matcher = TEMP_PIPE_NAME.matcher(name);

    if (matcher.matches()) {
      return matcher.group(1);
    } else {
      return name;
    }
  }

  /**
   * Subclasses can override this and call super() to add handling for other unrecognized classes
   *
   * @param tap
   * @return
   */
  private String getTapIdentifier(Tap tap, boolean removeRandomSuffixFromTempTaps){
    //  MemorySourceTap and NullTap both have  really annoying random identifiers that aren't important to note
    if (tap instanceof NullTap || tap instanceof MemorySourceTap) {
      return tap.getClass().getSimpleName();
    }

    //  concatenate all sources in a multi source tap
    else if (tap instanceof MultiSourceTap ) {
      MultiSourceTap multi = (MultiSourceTap) tap;
      List<String> sources = new ArrayList<String>();
      Iterator children = multi.getChildTaps();
      while (children.hasNext()) {
        Object t = children.next();
        if(t instanceof Tap){
          sources.add(getTapIdentifier((Tap) t, removeRandomSuffixFromTempTaps));
        }
      }
      return StringUtils.join(sources, "+");
    }

    else{
      return getPathName(tap, removeRandomSuffixFromTempTaps);
    }
  }

  private static String getPathName(Tap tap, boolean removeRandomSuffixFromTempTaps) {
    String id = tap.getIdentifier();
    if (id == null) {
      id = "null";
    }

    String name;
    if(tap instanceof Hfs){
      Path path = new Path(id);
      name = path.getName();
    }else{
      name = id;
    }

    // For temporary sources, we don't care about the random suffix appended by cascading
    if (tap.isTemporary() && removeRandomSuffixFromTempTaps) {
      name = getCanonicalName(name);
    }

    if (name.matches("^\\d+$")) {
      // For versioned stores, return "store_name/version_number", instead of just "version_number"
      String[] tokens = id.split("/");
      if (tokens.length > 1) {
        return tokens[tokens.length - 2] + "/" + name;
      } else {
        return name;
      }
    } else {
      return name;
    }
  }

}
