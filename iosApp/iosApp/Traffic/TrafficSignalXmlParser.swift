//
//  TrafficSignalXmlParser.swift
//  iosApp
//
//  서울 열린데이터광장 trafficSafetyA057PInfo XML 을 파싱해서
//  EPSG:5186 좌표를 WGS84 로 변환한 TrafficSignalEntity 리스트로 만든다.
//
//  Android 의 traffic/TrafficSignalXmlParser.kt 와 동일한 규칙:
//    - <row> 단위로 1건
//    - id = SIGNAL_ID / MNG_ID / ID 중 먼저 들어오는 비어있지 않은 값
//    - XCRD / YCRD = EPSG:5186 east / north (m)
//

import Foundation

enum TrafficSignalXmlParser {

    static func parse(xml: String) -> [TrafficSignalEntity] {
        guard let data = xml.data(using: .utf8) else {
            return []
        }
        let delegate = TrafficSignalXmlParserDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        return delegate.result
    }
}

private final class TrafficSignalXmlParserDelegate: NSObject, XMLParserDelegate {

    private(set) var result: [TrafficSignalEntity] = []

    private var currentTag: String?
    private var currentText: String = ""

    private var id: String?
    private var x: Double?
    private var y: Double?

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        currentTag = elementName
        currentText = ""

        if elementName == "row" {
            id = nil
            x = nil
            y = nil
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        currentText.append(string)
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        let text = currentText.trimmingCharacters(in: .whitespacesAndNewlines)

        switch elementName {
        case "SIGNAL_ID", "MNG_ID", "ID":
            if id == nil, !text.isEmpty {
                id = text
            }

        case "XCRD":
            x = Double(text)

        case "YCRD":
            y = Double(text)

        case "row":
            if let rawX = x, let rawY = y {
                let converted = CoordinateConverter.epsg5186ToWgs84(x: rawX, y: rawY)

                result.append(
                    TrafficSignalEntity(
                        id: id ?? "\(rawX)_\(rawY)",
                        xcrd: rawX,
                        ycrd: rawY,
                        lat: converted.latitude,
                        lon: converted.longitude,
                        updatedAt: Date().timeIntervalSince1970 * 1000
                    )
                )
            }

        default:
            break
        }

        currentTag = nil
        currentText = ""
    }
}
