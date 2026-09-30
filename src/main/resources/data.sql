-- rental-service/src/main/resources/data.sql
--
-- Fechas relativas a CURRENT_DATE para que la seed no envejezca: los FINALIZADO
-- quedan siempre en el pasado, los ACTIVO siempre cruzando el dia de hoy y los
-- PENDIENTE siempre a futuro, arranque el proyecto el dia que arranque.
--
-- INVARIANTE: dos arriendos en PENDIENTE o ACTIVO NO pueden solaparse sobre el mismo
-- auto (los FINALIZADO y CANCELADO si pueden, porque liberan las fechas). Revisado
-- auto por auto mas abajo. Si agregas filas, respeta esto o la validacion de
-- createRental rechazara arriendos legitimos despues.
--
-- Solo hay arriendos de usuarios CLIENT (ids 4-10). El admin (1) y los empleados
-- (2 y 3) operan el sistema, no arriendan a su nombre.
--
-- total_price = daily_rate del auto x dias del periodo.

INSERT INTO rentals (id, car_id, user_id, start_date, end_date, status, total_price) VALUES

    -- ---------- Historial (FINALIZADO) ----------
    (1,  1,  4, CURRENT_DATE - 60, CURRENT_DATE - 56, 'FINALIZADO', 180000),  -- Corolla  45.000 x 4
    (2,  2,  5, CURRENT_DATE - 55, CURRENT_DATE - 50, 'FINALIZADO', 310000),  -- Tucson   62.000 x 5
    (3,  5,  6, CURRENT_DATE - 45, CURRENT_DATE - 40, 'FINALIZADO', 390000),  -- Hilux    78.000 x 5
    (4,  1,  7, CURRENT_DATE - 30, CURRENT_DATE - 27, 'FINALIZADO', 135000),  -- Corolla  45.000 x 3
    (5, 10,  4, CURRENT_DATE - 25, CURRENT_DATE - 22, 'FINALIZADO', 264000),  -- BRZ      88.000 x 3 (auto hoy fuera de servicio)
    (6,  8,  8, CURRENT_DATE - 20, CURRENT_DATE - 15, 'FINALIZADO', 475000),  -- Model 3  95.000 x 5
    (7,  3,  9, CURRENT_DATE - 14, CURRENT_DATE - 10, 'FINALIZADO', 128000),  -- Sail     32.000 x 4

    -- ---------- Cancelados (liberan sus fechas) ----------
    (8,  6, 10, CURRENT_DATE - 12, CURRENT_DATE -  8, 'CANCELADO',  288000),  -- CX-5     72.000 x 4
    (9,  4,  5, CURRENT_DATE + 10, CURRENT_DATE + 14, 'CANCELADO',  100000),  -- Morning  25.000 x 4

    -- ---------- En curso (ACTIVO: hoy cae dentro del periodo) ----------
    (10, 2,  6, CURRENT_DATE -  3, CURRENT_DATE +  2, 'ACTIVO',     310000),  -- Tucson   62.000 x 5
    (11, 7,  8, CURRENT_DATE -  1, CURRENT_DATE +  4, 'ACTIVO',     425000),  -- Staria   85.000 x 5
    (12, 9,  4, CURRENT_DATE -  2, CURRENT_DATE +  1, 'ACTIVO',     156000),  -- Prius    52.000 x 3

    -- ---------- Reservas futuras (PENDIENTE) ----------
    (13, 1,  9, CURRENT_DATE +  5, CURRENT_DATE +  9, 'PENDIENTE',  180000),  -- Corolla  45.000 x 4
    (14, 2,  7, CURRENT_DATE +  6, CURRENT_DATE + 11, 'PENDIENTE',  310000),  -- Tucson   62.000 x 5
    (15, 3, 10, CURRENT_DATE +  3, CURRENT_DATE +  8, 'PENDIENTE',  160000),  -- Sail     32.000 x 5
    (16, 5,  5, CURRENT_DATE + 12, CURRENT_DATE + 17, 'PENDIENTE',  390000),  -- Hilux    78.000 x 5
    (17, 4,  6, CURRENT_DATE + 15, CURRENT_DATE + 20, 'PENDIENTE',  125000),  -- Morning  25.000 x 5
    (18, 8,  4, CURRENT_DATE + 20, CURRENT_DATE + 23, 'PENDIENTE',  285000),  -- Model 3  95.000 x 3
    (19, 1,  8, CURRENT_DATE + 12, CURRENT_DATE + 16, 'PENDIENTE',  180000),  -- Corolla  45.000 x 4
    (20, 7, 10, CURRENT_DATE +  8, CURRENT_DATE + 12, 'PENDIENTE',  340000)   -- Staria   85.000 x 4
ON CONFLICT DO NOTHING;

-- Comprobacion de solapamientos entre estados que ocupan (PENDIENTE / ACTIVO):
--   auto 1  (Corolla): [+5,+9] y [+12,+16]          -> separados
--   auto 2  (Tucson):  [-3,+2] y [+6,+11]           -> separados
--   auto 3  (Sail):    [+3,+8]                      -> unico
--   auto 4  (Morning): [+15,+20]                    -> unico (el [+10,+14] esta CANCELADO)
--   auto 5  (Hilux):   [+12,+17]                    -> unico
--   auto 6  (CX-5):    ninguno                      -> el unico esta CANCELADO
--   auto 7  (Staria):  [-1,+4] y [+8,+12]           -> separados
--   auto 8  (Model 3): [+20,+23]                    -> unico
--   auto 9  (Prius):   [-2,+1]                      -> unico
--   auto 10 (BRZ):     ninguno                      -> fuera de servicio, solo historial

SELECT setval(pg_get_serial_sequence('rentals', 'id'), COALESCE((SELECT MAX(id) FROM rentals), 1));