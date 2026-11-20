"""Held-out SMS challenge set: never used for training or threshold picking.

Written by hand (2026-11-21) in the style of real Indian SMS, to answer one question: does Shield call
ordinary promotional spam a scam? Three groups:

  promo   marketing from registered senders (DLT -P headers), with links, offers, "win", "cashback",
          "last day", toll-free numbers. Annoying, but not fraud. Must NOT be SCAM.
  service real transactional/service messages with links or scary words. Must NOT be SCAM.
  scam    fraud in phrasings that differ from the training templates. Must be SCAM (or at least flagged).

Labels: 0 = not a scam (promo/service), 1 = scam.
"""

PROMO = [
    ("JD-MYNTRA-P", "Last 6 hours! Extra 10% off on 1.2 lakh+ styles. Shop now: https://myntr.it/b9XkQ2 T&C"),
    ("AX-FLPKRT-P", "Big Billion Days are LIVE! Up to 80% off on mobiles, TVs & more. Shop: http://fkrt.it/Rk2mTTuuuN"),
    ("VM-AMAZON-P", "Great Indian Festival: Bank offers up to Rs 10,000 instant discount. Shop now https://amzn.to/3xYqP0a"),
    ("BZ-SWIGGY-P", "Hungry? 60% OFF up to Rs 120 on your next 3 orders! Code: SWIGGYIT. Order now swiggy.onelink.me/888564224"),
    ("JM-ZOMATO-P", "Weekend treat! Free delivery on all orders above Rs 199 today. Order now: https://zoma.to/r/xyz12"),
    ("VK-AIRTEL-P", "Get 2GB extra data FREE with Rs 349 recharge. Offer valid till tonight. Recharge on Airtel Thanks app: https://i.airtel.in/recharge"),
    ("CP-JIOINF-P", "Jio True 5G Unlimited! Recharge with Rs 349 and enjoy unlimited 5G data. Click https://www.jio.com/r/abcd to recharge"),
    ("AD-DOMINO-P", "Buy 1 Get 1 FREE on all medium pizzas today! Order now: https://dpz.in/3kd8a Call 1800-208-1234"),
    ("VM-BIGBSK-P", "Congratulations! You have won a Rs 100 cashback voucher on your next order above Rs 999. Use code BB100. bigbasket.com"),
    ("JD-AJIOCM-P", "Flat 50-90% off on top brands. Extra Rs 300 off on first order. Hurry, sale ends at midnight: https://ajio.me/aBc12"),
    ("AX-NYKAAF-P", "Your favourites are on sale! Up to 50% off + free gift on orders above Rs 999. Shop: https://nyk.ae/Xy9Q"),
    ("VM-PVRCIN-P", "Book 2 movie tickets and get Rs 150 off with code PVRFUN. Valid this weekend only. pvrcinemas.com"),
    ("BZ-MAKMTR-P", "Flight sale! Domestic fares from Rs 1,499. Book before 31 Nov. https://mmyt.co/Ab12Cd"),
    ("JM-LENSKA-P", "Buy 1 Get 1 on eyeglasses. Visit your nearest Lenskart store or shop at lskt.me/bogo. T&C apply"),
    ("VM-ICICIB-P", "Pre-approved Personal Loan of up to Rs 5 lakh at attractive rates. Apply on iMobile Pay. T&C apply. To opt out, SMS STOP to 5676766"),
    ("AD-HDFCBK-P", "Get 5% cashback on your HDFC Bank Millennia Credit Card spends. Apply now: https://hdfcbk.io/a/Xyz123 T&C"),
    ("VK-PHONPE-P", "Pay electricity bill on PhonePe and win cashback up to Rs 100! Pay now: https://phon.pe/ru_bill"),
    ("TX-PAYTMB-P", "Recharge your FASTag on Paytm and get assured cashback. Tap: https://p.paytm.me/xCTH/fastag"),
    ("JD-DECATH-P", "Winter sports sale: up to 40% off on jackets & shoes. In store and on decathlon.in. Hurry!"),
    ("VM-CROMA-P", "Mega Electronics Sale! No cost EMI + extra 10% off on HDFC cards. Shop: croma.com/sale"),
    ("AX-URBNCO-P", "Your home deserves a deep clean! Flat 25% off on cleaning services today. Book: https://urbn.co/u/xyz"),
    ("VM-RELDIG-P", "Exchange your old phone and get up to Rs 15,000 off on the new iPhone at Reliance Digital. Visit store today!"),
    ("BZ-UNACAD-P", "Last chance! Get 30% off on Unacademy Plus subscription. Offer expires tonight. Join: https://unacademy.com/plus"),
    ("JM-OLAAPP-P", "Ride more, save more! Flat 50% off up to Rs 75 on your next 2 rides. Code RIDE50. Book on Ola app"),
    ("VK-TATACL-P", "Tata CLiQ Luxury sale is LIVE! Up to 70% off on luxury brands. https://tatacliq.com/luxury"),
    ("AD-MEESHO-P", "Prices dropped! Sarees from Rs 199, kurtis from Rs 149. Free delivery. Shop now: https://meesho.com/s/p/abc"),
    ("VM-SBICRD-P", "Congratulations! You are eligible for an SBI Card with lifetime free offer. Apply: https://sbicard.com/apply T&C"),
    ("JD-BKMSHO-P", "Movie night? Get 1+1 on tickets with BookMyShow Stream. Offer for today only. bms.bz/AbC"),
    ("CP-VODAID-P", "Vi Hero Unlimited: Weekend data rollover + binge all night. Recharge Rs 299 now: https://vi.app.link/recharge"),
    ("AX-HAPPYM-P", "Win exciting prizes! Shop for Rs 999 at your nearest store and enter the lucky draw. Visit store today."),
    ("BZ-KFCIND-P", "Bucket deal at Rs 399 only! Order now on KFC app or call 1800-102-5322. Valid till stocks last"),
    ("VM-UPSTOX-P", "Open a free Demat account in 5 mins & get Rs 0 brokerage on delivery. https://upstox.com/open-account"),
]

SERVICE = [
    ("VM-HDFCBK-S", "Your HDFC Bank Credit Card statement for Nov-26 is ready. Total due Rs 8,450, min due Rs 430, due by 05-Dec. View: https://hdfcbk.io/s/abc"),
    ("AD-SBIINB-T", "Your A/c XX4321 is debited for Rs 2,000 on 20-11-26 by ATM withdrawal. Avl bal Rs 12,345. If not you, call 1800 1234 -SBI"),
    ("JD-AMAZON-S", "Your package will be delivered today by 8 PM. Track your package: https://amzn.in/d/abc123"),
    ("VK-DLVERY-S", "Your Delhivery shipment 1234567890 is out for delivery. Track: https://dlv.ry/xyz. Share OTP 4521 only at delivery."),
    ("AX-AIRTEL-S", "Your Airtel Xstream Fiber bill of Rs 1,178 is due on 25-Nov. Pay now to avoid disconnection: https://i.airtel.in/pay"),
    ("VM-BESCOM-S", "BESCOM: Bill for RR no 123456 Rs 845 due on 28-11-2026. Pay at bescom.co.in to avoid disconnection."),
    ("BZ-ICICIB-S", "Your ICICI Bank debit card will expire on 30-Nov-26. New card has been dispatched to your registered address."),
    ("JM-IRCTCI-S", "PNR 4523678901: Your ticket is WL 12. Chart prepares 4 hrs before departure. Check status: https://irctc.co.in"),
    ("VM-UIDAIS-G", "Your Aadhaar has been successfully updated. Download e-Aadhaar at https://myaadhaar.uidai.gov.in"),
    ("AD-EPFOHO-G", "Dear member, your claim for Rs 25,000 has been settled and sent to your bank account. -EPFO"),
    ("VM-KOTAKB-S", "Dear customer, your KYC is due for periodic update. Please visit your nearest Kotak branch or update on Kotak app. Ignore if done."),
    ("AX-PAYTMB-T", "Rs 500 sent to Ravi Kumar from your Paytm UPI. UPI Ref 412345678901. Not you? Call 0120-4456456"),
    ("JD-ZOMATO-S", "Your Zomato order #5678 is out for delivery. Your delivery partner Suresh will reach in 15 mins. Track: https://zoma.to/o/123"),
    ("CP-GOIBIB-S", "Booking confirmed! Hotel Sunrise, Goa, 12-14 Dec. Booking ID GO12345. Manage booking: https://go.ibi.bo/abc"),
    ("VK-JIOINF-S", "Your plan expires in 2 days. Recharge now to continue enjoying uninterrupted services. https://www.jio.com/selfcare/recharge"),
    ("VM-AXISBK-S", "Your Axis Bank account will be debited Rs 4,999 for EMI on 05-Dec. Please maintain sufficient balance. -Axis Bank"),
]

SCAM = [
    ("+919812345678", "Dear user, your HDFC NetBanking will be suspended today. Update PAN immediately: http://hdfc-netbank-update.xyz/pan"),
    ("+917003456789", "Congratulations! Your mobile number won Rs 25,00,000 in KBC lucky draw. Call Rana Pratap on 9876501234 to claim. Pay registration fee Rs 12,500"),
    ("VM-BLRPWR", "Dear consumer, your electricity connection will be disconnected tonight at 9.30 PM because previous month bill was not updated. Contact officer 8167429301"),
    ("+916290123456", "Part time job offer: earn Rs 3000-8000 daily by liking YouTube videos. Contact on WhatsApp wa.me/919812300000"),
    ("", "Your parcel could not be delivered due to incomplete address. Update within 24 hours: https://indiapost-redeliver.top/in"),
    ("+918876543210", "This is from Mumbai Cyber Crime. A case is registered against your Aadhaar for money laundering. Join video call or face arrest. Call 9123456780"),
    ("AD-REWARD", "Dear customer, 7,250 SBI reward points expire today! Redeem now by installing SBI Rewardz app: http://sbi-rewards-redeem.in/apk"),
    ("+919900112233", "Hi Mom, my phone broke, this is my new number. Need Rs 15,000 urgently for hospital, please send on UPI 9900112233@ybl"),
    ("", "Income Tax Dept: You are eligible for refund of Rs 15,490. Submit your account details at http://incometax-refund.online/claim"),
    ("+917788990011", "Your SIM card will be blocked in 2 hours due to KYC failure. Call customer care 7788990011 and share OTP to verify"),
    ("JM-ALERTS", "Your Paytm KYC is pending. Account will be closed today. Complete KYC: https://paytm-kyc-verify.web.app"),
    ("+918123456789", "Invest Rs 10,000 and get Rs 50,000 in 7 days guaranteed! Join our Telegram stock tips group: t.me/fastprofit99"),
    ("", "FedEx: your package containing illegal items is held at customs. Press 1 to talk to officer or police case will be filed"),
    ("+916000123456", "Instant loan Rs 50,000 approved without CIBIL! Download app http://quickloan-apk.click and pay Rs 499 processing fee"),
    ("VK-INFOSM", "Dear customer, Rs 9,999 was debited for Amazon Prime auto renewal. If not you, call 8800123456 to cancel and get refund"),
    ("+919123987654", "You have received a refund of Rs 2,500. Accept the UPI collect request and enter your PIN to receive the money"),
]


# Written after the gate was tuned on the groups above (so these are the fair test): the user's own
# reports from the Infinix (real OTPs, Jio/Airtel missed-call alerts flagged) and more of the same kind.
FRESH_HAM = [
    ("JM-JIOMCA-S", "You have a missed call from 9876543210 at 10:32 AM on 21-Nov-26. The caller did not leave a message. -Jio"),
    ("JX-JIOSVC-S", "Missed call alert: +91 98450 12345 tried to call you 2 times. Last call at 18:05. Call back now. -Jio"),
    ("VK-AIRMCA-S", "Missed call alert: 9123456780 called you at 09:14 on 21/11. Recharge with Rs 199 to stay connected. -Airtel"),
    ("AD-AIRTEL-S", "Hello! 8899776655 is now available to take calls. -Airtel"),
    ("VM-VIMCAL-S", "You missed 3 calls from 7012345678. Last call 21-Nov 11:40 PM. -Vi"),
    ("JD-AMAZON-S", "Your OTP for Amazon login is 482913. Valid for 10 minutes."),
    ("VM-SBIOTP-S", "OTP for your SBI transaction of Rs 2,340.00 at FLIPKART is 739201. Valid for 3 mins."),
    ("AX-PHONPE-S", "PhonePe: 551822 is your OTP to log in. It is valid for 5 minutes. #PhonePe"),
    ("BZ-GOOGLE-S", "G-482913 is your Google verification code."),
    ("TX-WHTSAP-S", "Your WhatsApp code: 123-456. You can also tap on this link to verify your phone: v.whatsapp.com/123456"),
    ("JM-JIOPAY-S", "Use 4821 as your OTP to recharge Rs 299 on MyJio. OTP is valid for 5 mins."),
    ("VM-IRCTCI-S", "OTP for IRCTC registration is 902114. Please enter this to complete registration."),
    ("CP-ICICIT-T", "Dear Customer, OTP for card ending 4321 is 845612 for txn of Rs 1,250 at SWIGGY. Valid 5 mins. If not you call 18002662"),
    ("VK-JIOINF-S", "Dear customer, your number 9876543210 has been ported in to Jio successfully. Enjoy services."),
    ("AD-AIRTEL-T", "Your data pack has expired. Recharge now to continue using data: https://i.airtel.in/data"),
]
FRESH_SCAM = [
    ("+918765432109", "Your Jio SIM will be blocked in 24 hrs due to incomplete KYC. Share the OTP sent to you with our executive at 8765432109"),
    ("+917654321098", "Airtel: you have won a Rs 5000 recharge in our lucky draw. Send the OTP you received to claim the prize."),
    ("", "Your missed call refund of Rs 1,499 is pending. Enter your UPI PIN on this link to receive it: http://refund-jio.online/upi"),
    ("JM-ALERTS", "Dear customer, your Airtel number will be deactivated today. Verify Aadhaar now at http://airtel-kyc.top/verify"),
]


def rows():
    out = []
    for group, lab, table in (("promo", 0, PROMO), ("service", 0, SERVICE), ("scam", 1, SCAM),
                              ("fresh_ham", 0, FRESH_HAM), ("fresh_scam", 1, FRESH_SCAM)):
        for sender, text in table:
            out.append(dict(text=text, sender=sender, label=lab, group=group))
    return out
