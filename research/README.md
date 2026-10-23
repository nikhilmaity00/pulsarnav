# Protocol Research & Reverse Engineering Scripts

This directory contains the standalone Node.js disassembler and inspection scripts used to analyze the **OTC Engineering BLE protocol** for Bajaj Pulsar & Dominar digital consoles:

- **`decrypt_uuids.js`**: Reconstructs the 128-bit BLE service & characteristic UUIDs from obfuscated bytecode arrays.
- **`disassemble_tbt.js`**: Analyzes bytecode instructions responsible for formatting the 48-byte Turn-by-Turn navigation packets.
- **`test_protocol_and_parser.js`**: Standalone unit verification test verifying checksum calculation and maneuver opcode matching.
- **`dump_*.js` / `inspect_*.js`**: Focused inspection routines extracting GATT callbacks, packet offsets, endianness, and telephony frame structures.
