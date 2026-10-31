"""simulation - имитация шлюза MK-531 и CA71 для проверки программы без оборудования."""
import random


class FakeGate:
    def __init__(self):
        self.ram = {1: 132, 2: 152}
        self.rom = dict(self.ram)

    def close(self):
        pass

    def find_interface(self):
        return b""

    def read_wiper(self, ch, rom=False):
        return (self.rom if rom else self.ram)[ch]

    def write_wiper(self, ch, v, rom=False, check=True):
        if not 0 <= v <= 256:
            raise ValueError("код должен быть 0..256")
        (self.rom if rom else self.ram)[ch] = v


class FakeCA71:
    def __init__(self, gate):
        self.gate, self.ch, self.sp, self.on = gate, 1, 0.0, False
        self.k = {1: 0.0011, 2: -0.0007}
        self.ideal = {1: 141, 2: 163}

    def close(self):
        pass

    def measure_setup(self):
        pass

    def source_function(self):
        return "4-20mA"

    def set_current(self, ma, verify=True):
        self.sp = ma

    def output(self, on):
        self.on = on

    def read_current_avg(self, n=3, delay=0):
        code = self.gate.ram[self.ch]
        i = self.sp * (1 + self.k[self.ch] * (code - self.ideal[self.ch]))
        return i + random.gauss(0, 0.0004) + 0.0012
