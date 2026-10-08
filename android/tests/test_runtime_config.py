"""Configuration validation protects on-device state across restarts."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).parents[1] / 'app/src/main/python/android_config.py'


class AndroidConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(), 'Android configuration adapter has not been implemented')
        spec = importlib.util.spec_from_file_location('android_config', MODULE)
        self.config = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.config)

    def test_initial_config_uses_private_persistent_database(self):
        with tempfile.TemporaryDirectory() as folder:
            path = self.config.prepare_config(folder)
            import yaml
            data = yaml.safe_load(path.read_text(encoding='utf-8'))
            self.assertEqual(data['server']['history_db_path'], str(Path(folder) / 'port_history.db'))
            self.assertFalse(data['mqtt']['enabled'])
            self.assertEqual(data['server']['host'], '127.0.0.1')

    def test_existing_configuration_survives_boot_and_blank_path_is_repaired(self):
        with tempfile.TemporaryDirectory() as folder:
            import yaml
            path = Path(folder) / 'config.yaml'
            path.write_text('ble:\n  mac: "AA:BB:CC:DD:EE:FF"\n  token: "00112233445566778899aabb"\nserver:\n  history_db_path: ""\n', encoding='utf-8')
            self.config.prepare_config(folder)
            data = yaml.safe_load(path.read_text(encoding='utf-8'))
            self.assertEqual(data['ble']['token'], '00112233445566778899aabb')
            self.assertEqual(data['server']['history_db_path'], str(Path(folder) / 'port_history.db'))

    def test_user_selected_cloud_integrations_survive_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            import yaml
            path = Path(folder) / 'config.yaml'
            path.write_text('mqtt:\n  enabled: true\nbemfa:\n  enabled: true\n', encoding='utf-8')
            self.config.prepare_config(folder)
            data = yaml.safe_load(path.read_text(encoding='utf-8'))
            self.assertTrue(data['mqtt']['enabled'])
            self.assertTrue(data['bemfa']['enabled'])

    def test_invalid_credential_is_rejected_and_masked_existing_value_is_allowed(self):
        self.assertIsNotNone(self.config.validate_update({'ble': {'token': 'xyz'}}))
        self.assertIsNone(self.config.validate_update({'ble': {'token': '0011****aabb'}}))
        self.assertIsNone(self.config.validate_update({'ble': {'token': '00112233445566778899aabb'}}))
        self.assertIsNotNone(self.config.validate_update({'server': {'port': 'bad'}}))
        self.assertIsNotNone(self.config.validate_update({'mqtt': {'enabled': 'false'}}))

    def test_unconfigured_device_is_not_considered_ready(self):
        self.assertFalse(self.config.device_ready('', ''))
        self.assertFalse(self.config.device_ready('XX:XX:XX:XX:XX:XX', 'aabb'))
        self.assertTrue(self.config.device_ready('AA:BB:CC:DD:EE:FF', '00112233445566778899aabb'))


if __name__ == '__main__':
    unittest.main()
