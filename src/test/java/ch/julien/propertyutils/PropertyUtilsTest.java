package ch.julien.propertyutils;

import static ch.julien.propertyutils.PropertyUtils.on;
import static ch.julien.propertyutils.PropertyUtils.property;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class PropertyUtilsTest {
	@Test
	public void test() {
		PropertyUtils.Argument<String> booleanArgument = property(on(Bar.class).getBaz());
		assertThat(booleanArgument.getInkvokedPropertyName()).isEqualTo("baz");
	}

	public static class Bar {
		private String baz;

		public String getBaz() {
			return this.baz;
		}

		public void setBaz(String baz) {
			this.baz = baz;
		}
	}
}