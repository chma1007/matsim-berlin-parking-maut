package org.matsim.run;

import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.contrib.roadpricing.RoadPricing;
import org.matsim.contrib.roadpricing.RoadPricingConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.controler.listener.StartupListener;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class RunOpenBerlinWithMaut extends OpenBerlinScenario {

	public static void main(String[] args) {
		run(RunOpenBerlinWithMaut.class, args);
	}

	// ✅ 配置路径方法（不加 @Override）
	protected URL getConfigUrl() {
		try {
			return new File("D:/berlin/input/v6.4/berlin-v6.4-1pct-maut.config.xml").toURI().toURL();
		} catch (MalformedURLException e) {
			throw new RuntimeException("❌ 无法找到 config 文件，请检查路径", e);
		}
	}

	@Override
	protected Config prepareConfig(Config config) {
		// 加载 RoadPricing 模块
		ConfigUtils.addOrGetModule(config, RoadPricingConfigGroup.class);
		return super.prepareConfig(config);
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);  // 保留 modal split 等 analysis

		// 加载收费模块
		RoadPricing.configure(controler);

		// 收集收费事件
		List<PersonMoneyEvent> tollEvents = new ArrayList<>();

		controler.addControlerListener(new StartupListener() {
			@Override
			public void notifyStartup(StartupEvent event) {
				event.getServices().getEvents().addHandler(new PersonMoneyEventHandler() {
					@Override
					public void handleEvent(PersonMoneyEvent event) {
						if ("toll".equals(event.getPurpose())) {
							tollEvents.add(event);
						}
					}

					@Override
					public void reset(int iteration) {
						tollEvents.clear();
					}
				});
			}
		});

		controler.addControlerListener(new ShutdownListener() {
			@Override
			public void notifyShutdown(ShutdownEvent event) {
				String outputFile = event.getServices().getControlerIO().getOutputFilename("toll_events.tsv");
				try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
					writer.write("personId\ttime\tamount\tpurpose\n");
					for (PersonMoneyEvent e : tollEvents) {
						writer.write(e.getPersonId() + "\t" + e.getTime() + "\t" + e.getAmount() + "\t" + e.getPurpose() + "\n");
					}
					System.out.println("✅ Toll event log written to: " + outputFile);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
	}
}
