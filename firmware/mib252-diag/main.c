/*
 * MIB-252 Ex (КДСА.426475.003) - диагностическая прошивка.
 * МК: STM32L052K8T6. Проверка входов АЦП (AD7792 через мост SC18IS602B)
 * и выходов ЦАП (DAC8750, цепочка из двух), индикация на светодиодах CH1/CH2.
 *
 * Последовательности обмена с микросхемами (адреса мостов, режимы SPI,
 * регистры AD7792, кадры DAC8750) повторяют рабочую прошивку MIB252_v30.
 */
#include <stdint.h>

#define REG(a)        (*(volatile uint32_t *)(a))

/* ---- RCC / FLASH / IWDG / SysTick ---- */
#define RCC_CR        REG(0x40021000)
#define RCC_CFGR      REG(0x4002100C)
#define RCC_IOPENR    REG(0x4002102C)
#define RCC_APB2ENR   REG(0x40021034)
#define RCC_APB1ENR   REG(0x40021038)
#define FLASH_ACR     REG(0x40022000)
#define IWDG_KR       REG(0x40003000)
#define SYST_CSR      REG(0xE000E010)
#define SYST_RVR      REG(0xE000E014)
#define SYST_CVR      REG(0xE000E018)

/* ---- GPIO ---- */
#define GPIOA         0x50000000u
#define GPIOB         0x50000400u
#define MODER(p)      REG((p) + 0x00)
#define OTYPER(p)     REG((p) + 0x04)
#define OSPEEDR(p)    REG((p) + 0x08)
#define PUPDR(p)      REG((p) + 0x0C)
#define IDR(p)        REG((p) + 0x10)
#define BSRR(p)       REG((p) + 0x18)
#define AFRL(p)       REG((p) + 0x20)

/* PA2 CLR1, PA3 /ALARM1, PA4 LATCH, PA5 SCLK, PA6 MISO, PA7 MOSI,
 * PA8 LED CH2, PA9 LED CH1 (активный 0), PB0 /ALARM2, PB1 CLR2,
 * PB6 SCL, PB7 SDA */
#define PIN_CLR1      (1u << 2)
#define PIN_ALARM1    (1u << 3)
#define PIN_LATCH     (1u << 4)
#define PIN_LED_CH2   (1u << 8)
#define PIN_LED_CH1   (1u << 9)
#define PIN_ALARM2    (1u << 0)
#define PIN_CLR2      (1u << 1)

/* ---- SPI1 ---- */
#define SPI1_CR1      REG(0x40013000)
#define SPI1_CR2      REG(0x40013004)
#define SPI1_SR       REG(0x40013008)
#define SPI1_DR8      (*(volatile uint8_t *)0x4001300C)

/* ---- I2C1 ---- */
#define I2C1_CR1      REG(0x40005400)
#define I2C1_CR2      REG(0x40005404)
#define I2C1_TIMINGR  REG(0x40005410)
#define I2C1_ISR      REG(0x40005418)
#define I2C1_ICR      REG(0x4000541C)
#define I2C1_RXDR     REG(0x40005424)
#define I2C1_TXDR     REG(0x40005428)
#define I2C_TXIS      (1u << 1)
#define I2C_RXNE      (1u << 2)
#define I2C_NACKF     (1u << 4)
#define I2C_STOPF     (1u << 5)

/* ---- коды диагностики ---- */
enum { ADC_OK = 0, ADC_NO_BRIDGE = 1, ADC_NO_ADC = 2, ADC_SELFTEST = 3,
       ADC_IN_OPEN = 4, ADC_IN_SHORT = 5, ADC_IN_OVER = 6 };
enum { DAC_OK = 0, DAC_ALARM_IDLE = 1, DAC_ALARM_LOAD = 2 };

/* Пороги */
#define LOOP_CODE_MIN   30000u   /* ток 20 мкА через R8 5,23 кОм: норма ~46900 */
#define LOOP_CODE_MAX   62000u
#define RTD_SHORT_CODE  500u     /* < ~5 Ом  (код * 5230/8/65536)            */
#define RTD_OVER_CODE   65000u   /* > ~650 Ом                                */

/* Коды ЦАП для диапазона 0-24 мА */
#define DAC_4MA   10923u
#define DAC_12MA  32768u
#define DAC_20MA  54613u
#define DAC_STEP_MS 3000u

static volatile uint32_t ms_ticks;
static volatile uint8_t  adc_code[2];
static volatile uint8_t  dac_code[2];
static volatile uint8_t  led_mode;        /* 0 - заставка, 1 - коды */

/* ------------------------------------------------------------------ */
/* Светодиоды                                                          */
static void led_set(unsigned ch, unsigned on)
{
    uint32_t pin = ch ? PIN_LED_CH2 : PIN_LED_CH1;
    BSRR(GPIOA) = on ? (pin << 16) : pin;          /* активный уровень 0 */
}

/* Один цикл индикации канала:
 *   нет ошибок  -> горит постоянно;
 *   иначе пауза 1 с, N коротких вспышек (код АЦП), пауза,
 *   M длинных вспышек (код ЦАП). */
#define T_GAP     1000u
#define T_S_ON    150u
#define T_S_PER   400u
#define T_MID     800u
#define T_L_ON    700u
#define T_L_PER   1000u

struct led_state { uint32_t t; uint8_t a, d; };
static struct led_state led[2];

static void led_tick(unsigned ch)
{
    struct led_state *s = &led[ch];
    uint32_t t, ta, td, total;

    if (s->a == 0 && s->d == 0) {            /* нет ошибок в текущем цикле */
        s->a = adc_code[ch];
        s->d = dac_code[ch];
        s->t = 0;
        if (s->a == 0 && s->d == 0) { led_set(ch, 1); return; }
    }
    ta = (uint32_t)s->a * T_S_PER;
    td = (uint32_t)s->d * T_L_PER;
    total = T_GAP + ta + (s->a && s->d ? T_MID : 0) + td;
    t = s->t;
    if (t < T_GAP) {
        led_set(ch, 0);
    } else if (t < T_GAP + ta) {
        led_set(ch, ((t - T_GAP) % T_S_PER) < T_S_ON);
    } else {
        uint32_t base = T_GAP + ta + (s->a && s->d ? T_MID : 0);
        if (t < base) led_set(ch, 0);
        else          led_set(ch, ((t - base) % T_L_PER) < T_L_ON);
    }
    if (++s->t >= total) {                   /* цикл закончен - взять новые коды */
        s->a = 0; s->d = 0;
    }
}

void SysTick_Handler(void)
{
    ms_ticks++;
    IWDG_KR = 0xAAAA;        /* сторожевой таймер может быть включён опциями */
    if (led_mode) {
        led_tick(0);
        led_tick(1);
    }
}

static void delay_ms(uint32_t ms)
{
    uint32_t t0 = ms_ticks;
    while ((uint32_t)(ms_ticks - t0) < ms) { }
}

/* ------------------------------------------------------------------ */
/* I2C1 (100 кГц при 16 МГц)                                           */
static void i2c_init(void)
{
    I2C1_CR1 = 0;
    I2C1_TIMINGR = 0x00303D5B;
    I2C1_CR1 = 1;
}

static void i2c_recover(void)
{
    I2C1_CR1 = 0;
    delay_ms(1);
    I2C1_ICR = 0x3F38;
    I2C1_CR1 = 1;
}

/* ожидание флага; 0 - ок, 1 - NACK/таймаут */
static int i2c_wait(uint32_t flag)
{
    uint32_t t0 = ms_ticks;
    for (;;) {
        uint32_t isr = I2C1_ISR;
        if (isr & flag) return 0;
        if (isr & I2C_NACKF) return 1;
        if ((uint32_t)(ms_ticks - t0) > 20) return 1;
    }
}

static int i2c_finish(int err)
{
    if (!err) err = i2c_wait(I2C_STOPF);
    if (I2C1_ISR & I2C_NACKF) err = 1;
    I2C1_ICR = I2C_STOPF | I2C_NACKF;
    if (err) i2c_recover();
    return err;
}

static int i2c_write(uint8_t addr, const uint8_t *p, unsigned n)
{
    int err = 0;
    I2C1_ICR = I2C_STOPF | I2C_NACKF;
    I2C1_CR2 = ((uint32_t)addr << 1) | (n << 16) | (1u << 25) | (1u << 13);
    while (n-- && !err) {
        err = i2c_wait(I2C_TXIS);
        if (!err) I2C1_TXDR = *p++;
    }
    return i2c_finish(err);
}

static int i2c_read(uint8_t addr, uint8_t *p, unsigned n)
{
    int err = 0;
    I2C1_ICR = I2C_STOPF | I2C_NACKF;
    I2C1_CR2 = ((uint32_t)addr << 1) | (1u << 10) | (n << 16) | (1u << 25) | (1u << 13);
    while (n-- && !err) {
        err = i2c_wait(I2C_RXNE);
        if (!err) *p++ = (uint8_t)I2C1_RXDR;
    }
    return i2c_finish(err);
}

/* ------------------------------------------------------------------ */
/* Мост SC18IS602B + AD7792                                            */
static const uint8_t bridge_addr[2] = { 0x29, 0x2A };

static int br_cmd(unsigned ch, uint8_t fn, uint8_t val)
{
    uint8_t b[2] = { fn, val };
    return i2c_write(bridge_addr[ch], b, 2);
}

static int br_init(unsigned ch)
{
    int e = br_cmd(ch, 0xF0, 0x09);              /* режим SPI как в штатной ПО */
    e |= br_cmd(ch, 0xF6, 0x0E);                 /* SS1..SS3 - линии GPIO      */
    e |= br_cmd(ch, 0xF7, 0x54);                 /* двухтактные выходы         */
    return e;
}

/* обмен по SPI через мост: n байт туда и обратно (n <= 4) */
static int br_spi(unsigned ch, const uint8_t *tx, uint8_t *rx, unsigned n)
{
    uint8_t b[5];
    unsigned i;
    b[0] = 0x01;                                 /* SS0 */
    for (i = 0; i < n; i++) b[i + 1] = tx[i];
    if (i2c_write(bridge_addr[ch], b, n + 1)) return 1;
    delay_ms(2);
    if (i2c_read(bridge_addr[ch], b, n)) return 1;
    for (i = 0; i < n; i++) rx[i] = b[i];
    return 0;
}

static int adc_reset(unsigned ch)
{
    static const uint8_t ff[4] = { 0xFF, 0xFF, 0xFF, 0xFF };
    uint8_t rx[4];
    int e = br_spi(ch, ff, rx, 4);
    delay_ms(2);
    return e;
}

static int adc_wr16(unsigned ch, uint8_t reg, uint16_t v)
{
    uint8_t tx[3] = { (uint8_t)(reg << 3), (uint8_t)(v >> 8), (uint8_t)v }, rx[3];
    return br_spi(ch, tx, rx, 3);
}

static int adc_wr8(unsigned ch, uint8_t reg, uint8_t v)
{
    uint8_t tx[2] = { (uint8_t)(reg << 3), v }, rx[2];
    return br_spi(ch, tx, rx, 2);
}

static int adc_rd16(unsigned ch, uint8_t reg, uint16_t *v)
{
    uint8_t tx[3] = { (uint8_t)(0x40 | (reg << 3)), 0, 0 }, rx[3];
    if (br_spi(ch, tx, rx, 3)) return 1;
    *v = (uint16_t)((rx[1] << 8) | rx[2]);
    return 0;
}

static int adc_rd8(unsigned ch, uint8_t reg, uint8_t *v)
{
    uint8_t tx[2] = { (uint8_t)(0x40 | (reg << 3)), 0 }, rx[2];
    if (br_spi(ch, tx, rx, 2)) return 1;
    *v = rx[1];
    return 0;
}

/* ждать /RDY; 0 - готово, 1 - ошибка шины, 2 - таймаут */
static int adc_wait_rdy(unsigned ch)
{
    uint32_t t0 = ms_ticks;
    uint8_t st;
    delay_ms(5);
    for (;;) {
        if (adc_rd8(ch, 0, &st)) return 1;
        if (!(st & 0x80)) return 0;
        if ((uint32_t)(ms_ticks - t0) > 800) return 2;
        delay_ms(5);
    }
}

/* Одно измерение. gpio - состояние ключей, cfg/io/mode - регистры AD7792,
 * cal - выполнить калибровку нуля. Возврат: 0, ADC_NO_BRIDGE или ADC_NO_ADC */
static int adc_measure(unsigned ch, uint8_t gpio, uint16_t cfg, uint8_t io,
                       uint16_t mode, int cal, uint16_t *res)
{
    uint16_t rb;
    int r;
    if (br_cmd(ch, 0xF4, gpio)) return ADC_NO_BRIDGE;
    if (adc_reset(ch)) return ADC_NO_BRIDGE;
    if (adc_wr16(ch, 2, cfg)) return ADC_NO_BRIDGE;
    if (adc_rd16(ch, 2, &rb)) return ADC_NO_BRIDGE;
    if (rb != cfg) return ADC_NO_ADC;
    if (adc_wr8(ch, 5, io)) return ADC_NO_BRIDGE;
    if (cal) {
        if (adc_wr16(ch, 1, 0x800A)) return ADC_NO_BRIDGE;
        r = adc_wait_rdy(ch);
        if (r) return r == 1 ? ADC_NO_BRIDGE : ADC_NO_ADC;
    }
    if (adc_wr16(ch, 1, mode)) return ADC_NO_BRIDGE;
    r = adc_wait_rdy(ch);
    if (r) return r == 1 ? ADC_NO_BRIDGE : ADC_NO_ADC;
    if (adc_rd16(ch, 3, res)) return ADC_NO_BRIDGE;
    return ADC_OK;
}

static uint8_t adc_test(unsigned ch)
{
    uint8_t id;
    uint16_t v;
    int r;

    /* 1. мост I2C-SPI (изолированное питание, изолятор I2C, сам мост) */
    if (br_init(ch)) return ADC_NO_BRIDGE;
    /* 2. AD7792: сброс и чтение ID (младшая тетрада 0xA) */
    if (adc_reset(ch)) return ADC_NO_BRIDGE;
    if (adc_rd8(ch, 4, &id)) return ADC_NO_BRIDGE;
    if ((id & 0x0F) != 0x0A) return ADC_NO_ADC;
    /* 3. внутренний контур: IOUT1 -> DA1(NC) -> R7 -> DA2(NC) -> R8.
     *    20 мкА на R8, канал AIN3 (REF+/REF-), внутр. опора, усиление 8 */
    r = adc_measure(ch, 0x01, 0x1392, 0x09, 0x0004, 1, &v);
    if (r) return (uint8_t)r;
    if (v < LOOP_CODE_MIN || v > LOOP_CODE_MAX) return ADC_SELFTEST;
    /* 4. внешний контур: IOUT1 -> R2 -> +IN .. датчик .. -IN -> R5 -> R8 */
    r = adc_measure(ch, 0x05, 0x1392, 0x09, 0x0004, 1, &v);
    if (r) return (uint8_t)r;
    if (v < LOOP_CODE_MIN) return ADC_IN_OPEN;
    /* 5. сопротивление на входе: AIN1, опора - R8, усиление 8, 2 x 210 мкА */
    r = adc_measure(ch, 0x05, 0x1310, 0x0A, 0x000B, 0, &v);
    if (r) return (uint8_t)r;
    if (v < RTD_SHORT_CODE) return ADC_IN_SHORT;
    if (v > RTD_OVER_CODE) return ADC_IN_OVER;
    return ADC_OK;
}

/* ------------------------------------------------------------------ */
/* DAC8750 x2 в цепочке: первым передаётся кадр для CH2 (DA8), вторым CH1 */
static void spi_byte(uint8_t b)
{
    uint32_t t0 = ms_ticks;
    while (!(SPI1_SR & 2)) if ((uint32_t)(ms_ticks - t0) > 5) return;
    SPI1_DR8 = b;
    while (!(SPI1_SR & 1)) if ((uint32_t)(ms_ticks - t0) > 5) return;
    (void)SPI1_DR8;
}

static void dac_latch(void)
{
    volatile int i;
    for (i = 0; i < 20; i++) { }
    BSRR(GPIOA) = PIN_LATCH;
    for (i = 0; i < 20; i++) { }
    BSRR(GPIOA) = PIN_LATCH << 16;
}

static void dac_frame(uint8_t a, uint16_t d)
{
    spi_byte(a); spi_byte((uint8_t)(d >> 8)); spi_byte((uint8_t)d);
}

static void dac_write2(uint8_t a, uint16_t d_ch1, uint16_t d_ch2)
{
    dac_frame(a, d_ch2);
    dac_frame(a, d_ch1);
    dac_latch();
}

static unsigned dac_alarm(unsigned ch)       /* 1 - авария (уровень 0) */
{
    return ch ? !(IDR(GPIOB) & PIN_ALARM2) : !(IDR(GPIOA) & PIN_ALARM1);
}

static void dac_init(void)
{
    /* импульс CLR на оба ЦАП */
    BSRR(GPIOA) = PIN_LATCH;
    BSRR(GPIOA) = PIN_CLR1;
    BSRR(GPIOB) = PIN_CLR2;
    delay_ms(1);
    BSRR(GPIOA) = PIN_LATCH << 16;
    BSRR(GPIOA) = PIN_CLR1 << 16;
    BSRR(GPIOB) = PIN_CLR2 << 16;
    delay_ms(1);
    dac_frame(0x55, 0x000D); dac_latch();    /* DCEN на первом в цепочке   */
    dac_write2(0x55, 0x000D, 0x000D);
    dac_write2(0x57, 0x0000, 0x0000);
    dac_write2(0x55, 0x000F, 0x000F);        /* диапазон 0-24 мА, выход выкл */
    dac_write2(0x01, 0x0000, 0x0000);
}

/* ------------------------------------------------------------------ */
static void hw_init(void)
{
    /* тактирование: HSI16, без PLL */
    FLASH_ACR |= 1;
    RCC_CR |= 1;
    while (!(RCC_CR & 4)) { }
    RCC_CFGR = (RCC_CFGR & ~3u) | 1;
    while ((RCC_CFGR & 0xC) != 0x4) { }

    RCC_IOPENR |= 3;                         /* GPIOA, GPIOB */
    RCC_APB2ENR |= 1u << 12;                 /* SPI1 */
    RCC_APB1ENR |= 1u << 21;                 /* I2C1 */

    /* светодиоды погашены, LATCH/CLR = 0 */
    BSRR(GPIOA) = PIN_LED_CH1 | PIN_LED_CH2 | ((PIN_LATCH | PIN_CLR1) << 16);
    BSRR(GPIOB) = PIN_CLR2 << 16;

    /* PA2 out, PA3 in+PU, PA4 out, PA5..7 AF0, PA8/PA9 out */
    MODER(GPIOA) = (MODER(GPIOA) & ~0x000FFFF0u) | 0x0005A910u;
    PUPDR(GPIOA) = (PUPDR(GPIOA) & ~0x000000C0u) | 0x00000040u;
    AFRL(GPIOA) &= ~0xFFF00000u;
    OSPEEDR(GPIOA) |= 0x0000A800u;
    /* PB0 in+PU, PB1 out, PB6/PB7 AF1 открытый сток с подтяжкой */
    MODER(GPIOB) = (MODER(GPIOB) & ~0x0000F00Fu) | 0x0000A004u;
    OTYPER(GPIOB) |= 0xC0;
    PUPDR(GPIOB) = (PUPDR(GPIOB) & ~0x0000F003u) | 0x00005001u;
    AFRL(GPIOB) = (AFRL(GPIOB) & ~0xFF000000u) | 0x11000000u;

    /* SysTick 1 мс */
    SYST_RVR = 16000 - 1;
    SYST_CVR = 0;
    SYST_CSR = 7;

    /* SPI1: мастер, CPOL=1 CPHA=1, 16 МГц/32 = 500 кГц */
    SPI1_CR1 = 0x0327;
    SPI1_CR2 = 0;
    SPI1_CR1 = 0x0367;

    i2c_init();
}

int main(void)
{
    static const uint16_t steps[3] = { DAC_4MA, DAC_12MA, DAC_20MA };
    uint8_t dac_idle[2];
    unsigned i, step = 0, ch;
    uint32_t t_step;

    hw_init();

    /* заставка: 3 поочерёдные вспышки - "диагностическая прошивка запущена" */
    for (i = 0; i < 3; i++) {
        led_set(0, 1); led_set(1, 0); delay_ms(200);
        led_set(0, 0); led_set(1, 1); delay_ms(200);
    }
    led_set(1, 0);
    delay_ms(300);                            /* запуск изолированных DC/DC */

    /* ЦАП: выходы выключены - аварии быть не должно */
    dac_init();
    delay_ms(100);
    for (ch = 0; ch < 2; ch++) {
        dac_idle[ch] = (uint8_t)dac_alarm(ch);
        dac_code[ch] = dac_idle[ch] ? DAC_ALARM_IDLE : DAC_OK;
    }
    /* включить выходы, ступень 4 мА */
    dac_write2(0x01, steps[0], steps[0]);
    dac_write2(0x55, 0x100F, 0x100F);
    t_step = ms_ticks;

    adc_code[0] = (uint8_t)adc_test(0);
    adc_code[1] = (uint8_t)adc_test(1);
    led_mode = 1;

    for (;;) {
        for (ch = 0; ch < 2; ch++) {
            adc_code[ch] = (uint8_t)adc_test(ch);

            if ((uint32_t)(ms_ticks - t_step) >= DAC_STEP_MS) {
                t_step = ms_ticks;
                step = (step + 1) % 3;
                dac_write2(0x55, 0x100F, 0x100F);
                dac_write2(0x01, steps[step], steps[step]);
            }
            for (i = 0; i < 2; i++) {
                if (dac_idle[i]) continue;    /* авария была ещё до включения */
                if ((uint32_t)(ms_ticks - t_step) < 100) continue;
                dac_code[i] = dac_alarm(i) ? DAC_ALARM_LOAD : DAC_OK;
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Запуск и таблица векторов                                           */
extern uint32_t _sidata, _sdata, _edata, _sbss, _ebss, _estack;

void Reset_Handler(void)
{
    uint32_t *s = &_sidata, *d = &_sdata;
    while (d < &_edata) *d++ = *s++;
    for (d = &_sbss; d < &_ebss; ) *d++ = 0;
    main();
    for (;;) { }
}

void Default_Handler(void)
{
    for (;;) { }
}

__attribute__((section(".isr_vector"), used))
static void (*const vectors[48])(void) = {
    (void (*)(void))&_estack,
    Reset_Handler,
    Default_Handler,            /* NMI */
    Default_Handler,            /* HardFault */
    0, 0, 0, 0, 0, 0, 0,
    Default_Handler,            /* SVC */
    0, 0,
    Default_Handler,            /* PendSV */
    SysTick_Handler,
    [16 ... 47] = Default_Handler,
};
