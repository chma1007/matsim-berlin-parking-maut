package org.matsim.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.parking.parkingcost.config.ParkingCostConfigGroup;
import org.matsim.contrib.parking.parkingcost.module.ParkingCostModule;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.simwrapper.SimWrapperConfigGroup;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class RunOpenBerlinWithParking extends OpenBerlinScenario {

	public static void main(String[] args) {
		run(RunOpenBerlinWithParking.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {
		// 注册必须模块
		config.addModule(new ParkingCostConfigGroup());
		config.addModule(new BicycleConfigGroup());
		config.addModule(new SimWrapperConfigGroup());
		return super.prepareConfig(config);
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler); // 保留 emissions/scoring 等配置

		// 添加 ParkingCost 模块
		controler.addOverridingModule(new ParkingCostModule());

		// 注册分析类：记录停车收费事件
		String outputDir = controler.getConfig().controller().getOutputDirectory();
		ParkingCostTracker tracker = new ParkingCostTracker(outputDir);

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(tracker);
				addControlerListenerBinding().toInstance(tracker);
			}
		});
	}

	static class ParkingCostTracker implements PersonMoneyEventHandler, StartupListener, ShutdownListener {
		private PrintWriter writer;
		private final String outputDir;
		private double totalCost = 0.0;

		public ParkingCostTracker(String outputDir) {
			this.outputDir = outputDir;
		}

		@Override
		public void notifyStartup(StartupEvent event) {
			try {
				writer = new PrintWriter(new FileWriter(outputDir + "/personCostEvents.tsv"));
				writer.println("time\tpersonId\tamount\ttype");
			} catch (IOException e) {
				throw new RuntimeException("❌ 无法写入 personCostEvents.tsv", e);
			}
		}

		@Override
		public void handleEvent(PersonMoneyEvent event) {
			if (event.getPurpose() != null && event.getPurpose().endsWith("parking cost")) {
				double cost = -event.getAmount(); // 金额是负的，代表支出
				totalCost += cost;
				writer.printf("%.1f\t%s\t%.2f\t%s%n",
					event.getTime(),
					event.getPersonId(),
					cost,
					event.getPurpose());
			}
		}

		@Override
		public void reset(int iteration) {}

		@Override
		public void notifyShutdown(ShutdownEvent event) {
			if (writer != null) writer.close();
			Logger log = LogManager.getLogger(RunOpenBerlinWithParking.class);
			log.info("✅ Total parking cost charged: " + totalCost + " €");
		}
	}
}
