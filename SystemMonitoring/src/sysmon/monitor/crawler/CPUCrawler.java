package sysmon.monitor.crawler;

import org.hyperic.sigar.CpuInfo;
import org.hyperic.sigar.CpuPerc;
import org.hyperic.sigar.SigarException;

import sysmon.common.metadata.CpuMetadata;
import sysmon.common.metadata.CpuMetadata.Core;

import com.google.gson.JsonObject;

/**
 * Crawl the information about system CPU.
 * 
 * @author yexijiang
 * 
 */
public class CPUCrawler extends Crawler<CpuMetadata> {

  public CPUCrawler(String crawlerName) {
    super(crawlerName);
  }

  @Override
  public String getCrawlerType() {
    return "cpu";
  }

  @Override
  protected void updateStaticMetaData() {
    try {
      CpuInfo[] cpuInfos = sigarProxy.getCpuInfoList();
      CpuInfo firstCPU = cpuInfos[0];
      this.staticMetaData.addProperty("totalCores", firstCPU.getTotalCores());
      this.staticMetaData.addProperty("vendor", firstCPU.getVendor());
      this.staticMetaData.addProperty("model", firstCPU.getModel());
      this.staticMetaData.addProperty("Mhz", firstCPU.getMhz());
    } catch (SigarException e) {
      e.printStackTrace();
    }
  }

  @Override
  protected void fetchDynamicMetaDataHelper(JsonObject newMetaData) {
    try {
      CpuPerc[] cpuPercs = sigarProxy.getCpuPercList();
      CpuMetadata.Core[] cores = new CpuMetadata.Core[cpuPercs.length];
      for (int i = 0; i < cpuPercs.length; ++i) {

        double userTime = cpuPercs[i].getUser();
        userTime = userTime < 1.0 ? userTime : 1.0;
        double sysTime = cpuPercs[i].getSys();
        sysTime = sysTime < 1.0 ? sysTime : 1.0;
        double combinedTime = cpuPercs[i].getCombined();
        combinedTime = combinedTime < 1.0 ? combinedTime : 1.0;
        double idleTime = cpuPercs[i].getIdle();
        idleTime = idleTime < 1.0 ? idleTime : 1.0;

        cores[i] = new CpuMetadata.Core(userTime, sysTime, combinedTime,
            idleTime);
      }
      this.metadataObject = new CpuMetadata(cores);
    } catch (SigarException e) {
      e.printStackTrace();
    }
  }

}
