package org.matsim.run;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.contrib.roadpricing.RoadPricing;
import org.matsim.contrib.roadpricing.RoadPricingConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.scoring.functions.PersonScoringParametersFromPersonAttributes;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.replanning.choosers.ForceInnovationStrategyChooser;
import org.matsim.core.replanning.choosers.StrategyChooser;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;

import java.io.*;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

public class RunOpenBerlinWithMaut extends OpenBerlinScenario {

	private static final Logger log = LogManager.getLogger(RunOpenBerlinWithMaut.class);

	private static final String ROADPRICING_FILE = "D:/berlin/hundekopf_distance_roadpricing_2.5_euro_per_km.xml";

	public static void main(String[] args) {
		run(RunOpenBerlinWithMaut.class, args);
	}

	protected URL getConfigUrl() {
		try {
			return new File("D:/berlin/input/v6.4/berlin-v6.4-1pct-maut.config.xml").toURI().toURL();
		} catch (MalformedURLException e) {
			throw new RuntimeException("❌ 无法找到 config 文件", e);
		}
	}

	@Override
	protected Config prepareConfig(Config config) {
		RoadPricingConfigGroup rp = ConfigUtils.addOrGetModule(config, RoadPricingConfigGroup.class);

		File f = new File(ROADPRICING_FILE);
		if (!f.exists() || f.isDirectory()) {
			throw new IllegalArgumentException("找不到 roadpricing 文件: " + f.getAbsolutePath());
		}
		rp.setTollLinksFile(f.getAbsolutePath());
		log.info("➡ Using roadpricing file: " + rp.getTollLinksFile());

		return super.prepareConfig(config);
	}

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);

		// 加载 Maut 模块
		RoadPricing.configure(controler);

		// ➕ contrib style 模块注册
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {

				// 启用 SwissRailRaptor（如果开启了公共交通）
				if (controler.getConfig().transit().isUseTransit()) {
					install(new SwissRailRaptorModule());
				}

				// 启用收入敏感评分
				bind(ScoringParametersForPerson.class)
					.to(PersonScoringParametersFromPersonAttributes.class)
					.in(Singleton.class);

				// 注册收费事件分析模块
				install(new PersonMoneyEventsAnalysisModule());

				// 注册强制创新机制（每 10 次强制策略）
				bind(new TypeLiteral<StrategyChooser<Plan, Person>>() {})
					.toInstance(new ForceInnovationStrategyChooser<>(10, ForceInnovationStrategyChooser.Permute.yes));
			}
		});

		// 自定义导出所有 toll 事件
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
					log.info("✅ Toll event log written to: " + outputFile);
				} catch (IOException e) {
					log.error("❌ Error writing toll events file", e);
				}
			}
		});
	}
}
